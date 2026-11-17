package local.jcc.probe;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.provider.Settings;
import android.widget.*;
import android.view.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import java.io.File;

public class MainActivity extends Activity {
    private TextView status, serviceBadge;
    private static final int BG = 0xff10141d, CARD = 0xff1b2230, FIELD = 0xff121923, INK = 0xffedf0f5, MUTED = 0xff9ba8bd, GOLD = 0xffe8c478;
    private EditText goldReserve;
    private CardPicker targetCards;
    private EditText deckNameInput, lineupCodes;
    private TextView codeSummary;
    private Button copyLineup, importLineup, confirmLineup;
    private String loadedTargets="";
    private Spinner deckSpinner;
    private DeckStore deckStore;
    private java.util.List<DeckStore.Deck> savedDecks;
    private String editingDeckId;
    private Switch autoRefresh;
    private Spinner championSeason;
    private CheckBox previewOnly;
    private Spinner captureScheme;
    private TextView captureHint;
    private static final int REQUEST_PROJECTION = 21;
    private android.media.projection.MediaProjectionManager projectionManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { update(); handler.postDelayed(this, 1000); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        deckStore = new DeckStore(this);
        LinearLayout layout = column();
        layout.setFocusableInTouchMode(true);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        layout.setPadding(dp(20), dp(20), dp(20), dp(28));
        LinearLayout brand = row();
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.jcc_icon);
        icon.setBackground(shape(FIELD, 16)); icon.setClipToOutline(true);
        brand.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout brandText = column(); brandText.setPadding(dp(12), 0, 0, 0);
        TextView title = label(getString(R.string.app_name), 21, INK);
        title.setTypeface(null, Typeface.BOLD); brandText.addView(title);
        brandText.addView(label("你的随身 D 牌搭档", 12, MUTED));
        brand.addView(brandText, new LinearLayout.LayoutParams(0, -2, 1));
        layout.addView(brand); space(layout, 22);

        LinearLayout connection = card(layout, "准备就绪，再上场", "选择卡组，显示悬浮窗后开启自动拿卡。");
        serviceBadge = label("", 12, GOLD); connection.addView(serviceBadge); space(connection, 8);
        status = label("", 13, MUTED); status.setLineSpacing(dp(3), 1); connection.addView(status);
        Button settings = button("无障碍设置  ›", false);
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addButton(connection, settings);

        LinearLayout codeCard = card(layout, "阵容码管理", "粘贴完整阵容码，可一次添加多套；名称会自动提取。");
        lineupCodes = input("【阵容码】#阵容名称#代码");
        lineupCodes.setMinLines(2); lineupCodes.setMaxLines(5); lineupCodes.setGravity(Gravity.TOP);
        lineupCodes.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        String shared = getIntent().getStringExtra(Intent.EXTRA_TEXT); if (shared != null) lineupCodes.setText(shared);
        codeCard.addView(lineupCodes);
        Button addCodes = button("添加 / 更新阵容码", true);
        addCodes.setOnClickListener(v -> {
            try {
                java.util.List<DeckStore.Deck> added = deckStore.addCodes(lineupCodes.getText().toString());
                DeckStore.Deck last = added.get(added.size()-1); reloadDeckChoices(last.id);
                if (CaptureService.instance != null) CaptureService.instance.selectDeck(last.id);
                lineupCodes.setText(""); Toast.makeText(this, "已添加或更新 " + added.size() + " 套阵容", Toast.LENGTH_LONG).show();
            } catch (RuntimeException e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
        }); addButton(codeCard, addCodes);

        LinearLayout decks = card(layout, "我的阵容", "选中阵容码后，可在悬浮窗自动导入游戏并识别英雄。");
        deckSpinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        deckSpinner.setBackground(shape(FIELD, 10));
        decks.addView(deckSpinner, new LinearLayout.LayoutParams(-1, dp(48)));
        codeSummary = label("", 12, GOLD); space(decks, 8); decks.addView(codeSummary);
        // 阵容预览图已移除（2026-11-17）：截图功能取消后这里仍在显示以前保存的旧图，
        // 表现为「复制阵容码」上面挂着一张过时的阵容截图，容易让人以为截图还在工作。
        LinearLayout codeActions = row(); copyLineup = button("复制阵容码", false); importLineup = button("在游戏中导入", false);
        weightedButton(codeActions, copyLineup); weightedButton(codeActions, importLineup); space(decks, 6); decks.addView(codeActions);
        copyLineup.setOnClickListener(v -> {
            DeckStore.Deck d = editingDeck(); if (d == null || !d.isCode()) return;
            ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText(d.name,d.code));
            Toast.makeText(this,"阵容码已复制",Toast.LENGTH_SHORT).show();
        });
        importLineup.setOnClickListener(v -> {
            try {
                DeckStore.Deck d = persistDeck();
                if (CaptureService.instance == null) { Toast.makeText(this,"请先开启无障碍服务",Toast.LENGTH_LONG).show(); return; }
                CaptureService.instance.requestLineupImport(d.id); CaptureService.instance.showOverlay();
                Toast.makeText(this,"请切回游戏；商店展开时会先收起，再导入第三套收藏并应用",Toast.LENGTH_LONG).show();
            } catch (RuntimeException e) { Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show(); }
        });
        space(decks, 14); decks.addView(label("卡组名称", 12, MUTED)); space(decks, 6);
        deckNameInput = input("给这套卡组起个名字"); deckNameInput.setSingleLine(true); decks.addView(deckNameInput);
        space(decks, 14); decks.addView(label("购买目标", 12, MUTED)); space(decks, 6);
        targetCards = new CardPicker(this);
        targetCards.setValue(getSharedPreferences("probe", MODE_PRIVATE).getString("targets", "可酷伯"));
        decks.addView(targetCards); space(decks, 10);
        confirmLineup = button("保存并确认英雄名单", false);
        confirmLineup.setOnClickListener(v -> {
            try {
                DeckStore.Deck d = persistDeck(); if (!d.isCode()) return;
                if (targetCards.value().isEmpty()) throw new IllegalArgumentException("请先添加英雄");
                d = deckStore.saveDerived(d.id,targetCards.value(),d.preview,true,""); reloadDeckChoices(d.id);
                if (CaptureService.instance != null) CaptureService.instance.selectDeck(d.id);
                Toast.makeText(this,"已确认，可开启自动拿卡",Toast.LENGTH_SHORT).show();
            } catch (RuntimeException e) { Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show(); }
        }); addButton(decks, confirmLineup);
        LinearLayout deckButtons = row();
        Button newDeck = button("新建", false);
        newDeck.setOnClickListener(v -> { deckSpinner.setSelection(0); newDeckEditor(); });
        Button saveDeck = button("保存卡组", true);
        saveDeck.setOnClickListener(v -> {
            try { DeckStore.Deck saved = persistDeck(); Toast.makeText(this, "已保存：" + saved.name, Toast.LENGTH_SHORT).show(); }
            catch (RuntimeException e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
        });
        Button deleteDeck = button("删除", false); deleteDeck.setTextColor(0xffefaaa1); deleteDeck.setOnClickListener(v -> deleteDeck());
        weightedButton(deckButtons, newDeck); weightedButton(deckButtons, saveDeck); weightedButton(deckButtons, deleteDeck);
        decks.addView(deckButtons);
        deckSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != deckSpinner.getSelectedItemPosition()) return;
                if (position == 0) { if (editingDeckId != null) newDeckEditor(); return; }
                if (savedDecks == null || position > savedDecks.size()) return;
                DeckStore.Deck deck = savedDecks.get(position - 1);
                if (deck.id.equals(editingDeckId)) return;
                loadDeckEditor(deck);
                if (CaptureService.instance != null) CaptureService.instance.selectDeck(deck.id); else deckStore.select(deck.id);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        LinearLayout options = card(layout, "运行偏好", "关闭自动刷新时，手动刷新也会自动买卡。");
        LinearLayout captureRow = row();
        LinearLayout captureText = column(); captureText.addView(label("取帧方案", 15, INK));
        captureText.addView(label("决定从哪里拿画面，识别与点击流程一致", 12, MUTED));
        captureRow.addView(captureText, new LinearLayout.LayoutParams(0, -2, 1));
        captureScheme = new Spinner(this, Spinner.MODE_DROPDOWN);
        captureScheme.setBackground(shape(FIELD, 10));
        ArrayAdapter<String> captureAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, CaptureScheme.LABELS) {
            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getView(position, recycled, parent);
                view.setTextColor(INK); view.setTextSize(14); view.setPadding(dp(10), 0, dp(10), 0); return view;
            }
            @Override public View getDropDownView(int position, View recycled, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, recycled, parent);
                view.setTextColor(INK); view.setBackgroundColor(CARD); view.setPadding(dp(14), dp(12), dp(14), dp(12)); return view;
            }
        };
        captureAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        captureScheme.setAdapter(captureAdapter);
        captureScheme.setSelection(CaptureScheme.index(CaptureScheme.selected(getSharedPreferences("probe", MODE_PRIVATE))));
        captureScheme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String scheme = CaptureScheme.fromIndex(position);
                getSharedPreferences("probe", MODE_PRIVATE).edit().putString(CaptureScheme.PREF, scheme).apply();
                if (CaptureService.instance != null) CaptureService.instance.setCaptureScheme(scheme);
                updateCaptureHint();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        captureRow.addView(captureScheme, new LinearLayout.LayoutParams(dp(150), dp(48)));
        options.addView(captureRow); space(options, 8);
        captureHint = label("", 12, MUTED); captureHint.setLineSpacing(dp(3), 1); options.addView(captureHint);
        Button authorize = button("授权 / 重新授权投屏取帧", false);
        authorize.setOnClickListener(v -> requestProjection());
        addButton(options, authorize);
        space(options, 10);
        LinearLayout seasonRow = row();
        LinearLayout seasonText = column(); seasonText.addView(label("棋子赛季", 15, INK));
        seasonText.addView(label("影响搜索选卡、费用兜底与阵容头像", 12, MUTED));
        seasonRow.addView(seasonText, new LinearLayout.LayoutParams(0, -2, 1));
        championSeason = new Spinner(this, Spinner.MODE_DROPDOWN);
        championSeason.setBackground(shape(FIELD, 10));
        java.util.List<String> seasonLabels = new java.util.ArrayList<>();
        for (int set : ChampionCatalog.SETS) seasonLabels.add(ChampionCatalog.label(set) + (set == ChampionCatalog.DEFAULT_SET ? "（默认）" : "（返场）"));
        ArrayAdapter<String> seasonAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, seasonLabels) {
            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getView(position, recycled, parent);
                view.setTextColor(INK); view.setTextSize(14); view.setPadding(dp(10), 0, dp(10), 0); return view;
            }
            @Override public View getDropDownView(int position, View recycled, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, recycled, parent);
                view.setTextColor(INK); view.setBackgroundColor(CARD); view.setPadding(dp(14), dp(12), dp(14), dp(12)); return view;
            }
        };
        seasonAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        championSeason.setAdapter(seasonAdapter);
        championSeason.setSelection(seasonIndex(ChampionCatalog.selectedSet(getSharedPreferences("probe", MODE_PRIVATE))));
        championSeason.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= ChampionCatalog.SETS.length) return;
                int set = ChampionCatalog.SETS[position];
                if (set == targetCards.catalogSet()) return;
                try {
                    ChampionCatalog next = ChampionCatalog.of(MainActivity.this, set);
                    int missing = 0;
                    for (String name : BuyPolicy.parseTargets(targetCards.value())) if (!next.has(name)) missing++;
                    targetCards.setCatalog(next);
                    getSharedPreferences("probe", MODE_PRIVATE).edit().putInt("championSet", set).apply();
                    if (CaptureService.instance != null) CaptureService.instance.setChampionSet(set);
                    if (missing > 0) Toast.makeText(MainActivity.this,
                        "已切换到 " + ChampionCatalog.label(set) + "；当前名单有 " + missing + " 个棋子不在该赛季，请检查购买目标",
                        Toast.LENGTH_LONG).show();
                } catch (RuntimeException e) { Toast.makeText(MainActivity.this, "切换赛季失败：" + e.getMessage(), Toast.LENGTH_LONG).show(); }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        seasonRow.addView(championSeason, new LinearLayout.LayoutParams(dp(132), dp(48)));
        options.addView(seasonRow);
        space(options, 10);
        autoRefresh = new Switch(this); autoRefresh.setText("自动刷新商店"); autoRefresh.setTextSize(15);
        autoRefresh.setTextColor(INK); tintToggle(autoRefresh);
        autoRefresh.setChecked(getSharedPreferences("probe", MODE_PRIVATE).getBoolean("autoRefresh", false));
        options.addView(autoRefresh, new LinearLayout.LayoutParams(-1, dp(48)));
        space(options, 10);
        LinearLayout reserveRow = row();
        LinearLayout reserveText = column(); reserveText.addView(label("保留金币", 15, INK));
        reserveText.addView(label("仅限制自动刷新", 12, MUTED));
        reserveRow.addView(reserveText, new LinearLayout.LayoutParams(0, -2, 1));
        goldReserve = input("0"); goldReserve.setSingleLine(true); goldReserve.setGravity(Gravity.CENTER);
        goldReserve.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        goldReserve.setText(String.valueOf(getSharedPreferences("probe", MODE_PRIVATE).getInt("reserve", GoldReserve.DEFAULT)));
        reserveRow.addView(goldReserve, new LinearLayout.LayoutParams(dp(80), dp(48))); options.addView(reserveRow);
        space(options, 14);
        previewOnly = new CheckBox(this); previewOnly.setText("仅识别，不执行点击"); previewOnly.setTextSize(13); previewOnly.setTextColor(MUTED);
        previewOnly.setButtonTintList(toggleColors());
        previewOnly.setChecked(getSharedPreferences("probe", MODE_PRIVATE).getBoolean("previewOnly", false)); options.addView(previewOnly);

        Button floating = button("显示悬浮窗", true);
        floating.setOnClickListener(v -> {
            if (!saveSettings()) return;
            if (CaptureService.instance == null) Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show();
            else CaptureService.instance.showOverlay();
        });
        addButton(layout, floating);
        TextView helper = label("自动保存当前卡组与运行设置", 12, MUTED); helper.setGravity(Gravity.CENTER);
        layout.addView(helper); space(layout, 12);
        LinearLayout actions = row();
        Button save = button("保存设置", false); save.setOnClickListener(v -> saveSettings()); weightedButton(actions, save);
        Button hide = button("关闭悬浮窗", false);
        hide.setOnClickListener(v -> { if (CaptureService.instance != null) CaptureService.instance.hideOverlay(); }); weightedButton(actions, hide);
        layout.addView(actions); space(layout, 18);

        LinearLayout tools = column(); tools.setPadding(dp(16), dp(4), dp(16), dp(12)); tools.setBackground(shape(CARD, 16));
        layout.addView(tools);
        Button toolsToggle = button("截图与诊断  ＋", false); addButton(tools, toolsToggle);
        LinearLayout diagnostics = column(); diagnostics.setVisibility(View.GONE); tools.addView(diagnostics);
        toolsToggle.setOnClickListener(v -> {
            boolean show = diagnostics.getVisibility() != View.VISIBLE;
            diagnostics.setVisibility(show ? View.VISIBLE : View.GONE);
            toolsToggle.setText(show ? "截图与诊断  −" : "截图与诊断  ＋");
        });
        Button capture = button("8 秒后截图", false);
        capture.setOnClickListener(v -> {
            CaptureService service = CaptureService.instance;
            if (service == null) Toast.makeText(this, "请先开启本应用的无障碍服务", Toast.LENGTH_LONG).show();
            else service.scheduleCapture();
            update();
        }); addButton(diagnostics, capture);
        Button preview = button("查看最近截图", false); preview.setOnClickListener(v -> showPreview()); addButton(diagnostics, preview);
        Button exportTrace = button("导出诊断日志", false); exportTrace.setOnClickListener(v -> exportTrace()); addButton(diagnostics, exportTrace);
        Button clearTrace = button("清空诊断日志", false); clearTrace.setTextColor(0xffefaaa1);
        clearTrace.setOnClickListener(v -> clearTrace()); addButton(diagnostics, clearTrace);
        String build = "v" + versionName(this);
        TextView version = label(build + "  ·  本地离线识别", 11, MUTED);
        version.setGravity(Gravity.CENTER); space(layout, 22); layout.addView(version);

        FrameLayout centered = new FrameLayout(this);
        FrameLayout.LayoutParams content = new FrameLayout.LayoutParams(dp(600), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        centered.addView(layout, content);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        scroll.addView(centered); scroll.setClipToPadding(false);
        scroll.setPadding(0, statusBarHeight(), 0, dp(16));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom;
            scroll.setPadding(bars.left, Math.max(bars.top, statusBarHeight()), bars.right, Math.max(bars.bottom, keyboard));
            return insets;
        });
        centered.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = Math.min(dp(600), r - l);
            if (content.width != width) { content.width = width; layout.setLayoutParams(content); }
        });
        reloadDeckChoices(deckStore.current().id);
        setContentView(scroll); scroll.requestApplyInsets(); layout.requestFocus(); update();
    }
    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(28);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static int seasonIndex(int set) {
        for (int i = 0; i < ChampionCatalog.SETS.length; i++) if (ChampionCatalog.SETS[i] == set) return i;
        return 0;
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private TextView label(String text, int size, int color) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color); return v;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private void space(LinearLayout parent, int size) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(size))); }
    private LinearLayout card(LinearLayout parent, String title, String subtitle) {
        LinearLayout v = column(); v.setPadding(dp(18), dp(18), dp(18), dp(18)); v.setBackground(shape(CARD, 18));
        TextView heading = label(title, 17, INK); heading.setTypeface(null, Typeface.BOLD); v.addView(heading);
        space(v, 6); TextView hint = label(subtitle, 12, MUTED); hint.setLineSpacing(dp(3), 1); v.addView(hint); space(v, 16);
        parent.addView(v, new LinearLayout.LayoutParams(-1, -2)); space(parent, 14); return v;
    }
    private EditText input(String hint) {
        EditText v = new EditText(this); v.setTextSize(15); v.setTextColor(INK); v.setHintTextColor(0xff6d7b90); v.setHint(hint);
        v.setPadding(dp(12), dp(12), dp(12), dp(12)); v.setBackground(shape(FIELD, 10)); v.setSelectAllOnFocus(false); return v;
    }
    private Button button(String text, boolean primary) {
        Button v = new Button(this); v.setText(text); v.setTextSize(14); v.setAllCaps(false);
        v.setTypeface(null, Typeface.BOLD); v.setTextColor(primary ? BG : INK);
        v.setBackgroundTintList(null); v.setBackground(new android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(primary ? 0x22000000 : 0x22ffffff), shape(primary ? GOLD : 0xff283246, 12), null));
        v.setMinHeight(dp(48)); v.setMinimumHeight(dp(48)); v.setMinWidth(0); v.setMinimumWidth(0);
        v.setPadding(dp(8), 0, dp(8), 0); v.setStateListAnimator(null); return v;
    }
    private void addButton(LinearLayout parent, Button button) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48)); p.topMargin = dp(8); p.bottomMargin = dp(8); parent.addView(button, p);
    }
    private void weightedButton(LinearLayout row, Button button) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1); p.setMargins(dp(3), 0, dp(3), 0); row.addView(button, p);
    }
    private ColorStateList toggleColors() {
        return new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{GOLD, 0xff728097});
    }
    private void tintToggle(Switch toggle) {
        toggle.setThumbTintList(toggleColors());
        toggle.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{0xff8a7042, 0xff3b4659}));
    }
    private void showPreview() {
        File file = new File(getFilesDir(), "latest.png");
        if (!file.exists()) { Toast.makeText(this, "还没有成功的截图", Toast.LENGTH_SHORT).show(); return; }
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options(); opts.inSampleSize = 2;
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), opts); if (bitmap == null) return;
        ImageView image = new ImageView(this); image.setAdjustViewBounds(true); image.setImageBitmap(bitmap);
        new android.app.AlertDialog.Builder(this).setTitle("最近一次截图").setView(image)
            .setPositiveButton("关闭", (d, w) -> {}).setOnDismissListener(d -> bitmap.recycle()).show();
    }
    /**
     * 导出诊断日志：合并当前与上一份 trace，写入系统「下载」目录（无需 INTERNET 或额外依赖），
     * 成功后弹出分享面板；用户也可直接从文件管理器取走该文件。
     */
    private void exportTrace() {
        File trace = new File(getFilesDir(), "assist-trace.log");
        File previous = new File(getFilesDir(), "assist-trace.previous.log");
        if (!trace.exists() && !previous.exists()) {
            Toast.makeText(this, "还没有诊断日志，请先开启一次自动拿卡", Toast.LENGTH_LONG).show(); return;
        }
        try {
            StringBuilder merged = new StringBuilder();
            merged.append("# 金铲铲助手诊断日志\n");
            merged.append("# 导出时间 ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date())).append('\n');
            for (File part : new File[]{previous, trace}) {
                if (!part.exists()) continue;
                merged.append("\n# === ").append(part.getName()).append(" (").append(part.length()).append(" bytes) ===\n");
                merged.append(new String(java.nio.file.Files.readAllBytes(part.toPath()), java.nio.charset.StandardCharsets.UTF_8));
                merged.append('\n');
            }
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
            String name = "jcc-trace-" + stamp + ".log";
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/JccProbe");
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new java.io.IOException("无法创建下载文件");
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("无法写入下载文件");
                out.write(merged.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            values.clear();
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            final String message = "日志已导出：下载/JccProbe/" + name;
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            try {
                android.content.Intent share = new android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "金铲铲助手诊断日志 " + stamp)
                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(android.content.Intent.createChooser(share, "发送诊断日志"));
            } catch (RuntimeException ignored) {
                // 没有可分享的应用时不影响文件已落盘。
            }
        } catch (Exception e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    private void clearTrace() {
        new android.app.AlertDialog.Builder(this).setTitle("清空诊断日志")
            .setMessage("清空后无法再查看之前的记录，仅在准备重新测试时使用。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空", (d, w) -> {
                for (String name : new String[]{"assist-trace.log", "assist-trace.previous.log"}) {
                    File file = new File(getFilesDir(), name);
                    if (file.exists() && !file.delete()) { Toast.makeText(this, "删除失败：" + name, Toast.LENGTH_LONG).show(); return; }
                }
                Toast.makeText(this, "诊断日志已清空", Toast.LENGTH_SHORT).show();
            }).show();
    }
    /** 读取安装包版本号，避免页脚版本文案与 build.gradle 脱节。 */
    private static String versionName(android.content.Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) { return "?"; }
    }
    /**
     * 申请投屏取帧授权。系统每次会话都要重新确认，因此“重新授权”即重新走一遍这个弹窗；
     * Android 14+ 还要求先有 mediaProjection 前台服务，这一步在 {@link ProjectionService} 内完成。
     */
    private void requestProjection() {
        if (projectionManager == null)
            projectionManager = (android.media.projection.MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (projectionManager == null) {
            Toast.makeText(this, "此设备不支持投屏取帧，请使用无障碍截图", Toast.LENGTH_LONG).show();
            return;
        }
        // 常驻通知需要通知权限才能显示（被拒绝也不影响取帧，只是少一条常驻提示）。
        // Android 13 以下没有这个权限，直接申请会拿到一次无意义的请求。
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQUEST_PROJECTION + 1); }
            catch (RuntimeException ignored) {}
        }
        try {
            // 部分国产 ROM（实测 K80 / HyperOS）的授权弹窗默认选「单个应用」，
            // 那样只能录到本应用自己的界面、拍不到游戏，识别必然全黑。
            // Android 14+ 用 MediaProjectionConfig 显式要求整个屏幕，弹窗就不再提供单应用选项。
            Intent capture;
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                capture = projectionManager.createScreenCaptureIntent(
                    android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay());
            } else {
                capture = projectionManager.createScreenCaptureIntent();
            }
            startActivityForResult(capture, REQUEST_PROJECTION);
        } catch (RuntimeException e) {
            Toast.makeText(this, "无法打开投屏授权：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PROJECTION) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "已取消投屏授权", Toast.LENGTH_SHORT).show();
            updateCaptureHint();
            return;
        }
        ProjectionService.start(this, resultCode, data);
        getSharedPreferences("probe", MODE_PRIVATE).edit().putString(CaptureScheme.PREF, CaptureScheme.PROJECTION).apply();
        if (captureScheme != null) captureScheme.setSelection(CaptureScheme.index(CaptureScheme.PROJECTION));
        if (CaptureService.instance != null) CaptureService.instance.setCaptureScheme(CaptureScheme.PROJECTION);
        Toast.makeText(this, "投屏取帧已授权，可显示悬浮窗开始识别", Toast.LENGTH_LONG).show();
        // 会话建立与虚拟显示创建是异步的，稍后再刷新状态文案。
        handler.postDelayed(this::updateCaptureHint, 600);
    }
    /** 刷新取帧方案的状态说明：让“已选投屏但还没授权”这种状态一眼可见。 */
    private void updateCaptureHint() {
        if (captureHint == null) return;
        String scheme = CaptureScheme.selected(getSharedPreferences("probe", MODE_PRIVATE));
        if (CaptureScheme.PROJECTION.equals(scheme)) {
            boolean ready = ProjectionStream.ready();
            captureHint.setTextColor(ready ? 0xff77d9b4 : GOLD);
            captureHint.setText(ready
                ? "投屏常驻流已就绪：识别实时取最新画面，不再受系统 334ms 截图间隔限制。"
                : "尚未授权投屏取帧（" + (ProjectionService.lastError.isEmpty() ? "点下方按钮授权" : ProjectionService.lastError) +
                  "）。未授权时识别会停在等待状态。");
        } else {
            captureHint.setTextColor(MUTED);
            captureHint.setText("无障碍截图：由系统逐次截图，默认方案；两次截图至少间隔约 334ms。");
        }
    }
    private DeckStore.Deck editingDeck() {
        if (deckStore != null && editingDeckId != null) for (DeckStore.Deck d : deckStore.all()) if (d.id.equals(editingDeckId)) return d;
        return null;
    }
    private void updateLineupView(DeckStore.Deck deck) {
        if (codeSummary == null) return;
        boolean code=deck!=null&&deck.isCode();copyLineup.setEnabled(code);importLineup.setEnabled(code);
        if (confirmLineup!=null) confirmLineup.setVisibility(code?View.VISIBLE:View.GONE);
        codeSummary.setText(!code?"手动卡组":deck.verified?"阵容码 · 英雄已确认":"阵容码 · "+(deck.note.isEmpty()?"首次导入后识别英雄":deck.note));
    }
    private void newDeckEditor() {
        editingDeckId = null; deckNameInput.setText(""); targetCards.setValue(""); loadedTargets="";updateLineupView(null);deckNameInput.requestFocus();
    }
    private void loadDeckEditor(DeckStore.Deck deck) {
        editingDeckId = deck.id; deckNameInput.setText(deck.name); targetCards.setValue(deck.targets);loadedTargets=deck.targets;updateLineupView(deck);
    }
    private void reloadDeckChoices(String selected) {
        savedDecks = deckStore.all();
        java.util.List<String> labels = new java.util.ArrayList<>(); labels.add("＋ 新建卡组"); int position = 0;
        for (int i = 0; i < savedDecks.size(); i++) {
            DeckStore.Deck deck = savedDecks.get(i); labels.add(deck.name);
            if (deck.id.equals(selected)) { position = i + 1; loadDeckEditor(deck); }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getView(position, recycled, parent);
                view.setTextColor(INK); view.setTextSize(15); view.setPadding(dp(12), dp(8), dp(12), dp(8)); return view;
            }
            @Override public View getDropDownView(int position, View recycled, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, recycled, parent);
                view.setTextColor(INK); view.setBackgroundColor(CARD); view.setPadding(dp(16), dp(14), dp(16), dp(14)); return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deckSpinner.setAdapter(adapter); deckSpinner.setSelection(position);
    }
    private DeckStore.Deck persistDeck() {
        DeckStore.Deck saved = deckStore.save(editingDeckId, deckNameInput.getText().toString(), targetCards.value());
        reloadDeckChoices(saved.id);
        if (CaptureService.instance != null) CaptureService.instance.selectDeck(saved.id);
        return saved;
    }
    private void deleteDeck() {
        if (editingDeckId == null) { Toast.makeText(this, "当前卡组尚未保存", Toast.LENGTH_SHORT).show(); return; }
        final String deleting = editingDeckId;
        new android.app.AlertDialog.Builder(this).setTitle("删除卡组")
            .setMessage("删除“" + deckNameInput.getText() + "”？")
            .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> {
                try {
                    DeckStore.Deck current = deckStore.delete(deleting); reloadDeckChoices(current.id);
                    if (CaptureService.instance != null) CaptureService.instance.selectDeck(current.id);
                } catch (RuntimeException e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
            }).show();
    }
    private boolean saveSettings() {
        try {
            java.util.List<String> targets = BuyPolicy.parseTargets(targetCards.value());
            if (targets.isEmpty() && (editingDeck() == null || !editingDeck().isCode())) throw new IllegalArgumentException("请填写至少一个目标卡名");
            int reserve = Integer.parseInt(goldReserve.getText().toString().trim());
            if (reserve < 0 || reserve > 999) throw new IllegalArgumentException("保留金币应为 0～999");
            persistDeck();
            getSharedPreferences("probe", MODE_PRIVATE).edit().putString("targets", String.join("\n", targets))
                .putInt("reserve", reserve).putBoolean("autoRefresh", autoRefresh.isChecked())
                .putBoolean("previewOnly", previewOnly.isChecked()).apply();
            if (CaptureService.instance != null) CaptureService.instance.hideOverlay();
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
            return true;
        } catch (RuntimeException e) { Toast.makeText(this, "设置无效：" + e.getMessage(), Toast.LENGTH_LONG).show(); return false; }
    }
    private void update() {
        boolean connected = CaptureService.instance != null;
        serviceBadge.setText(connected ? "● 无障碍服务已连接" : "○ 需要开启无障碍服务");
        serviceBadge.setTextColor(connected ? 0xff77d9b4 : GOLD);
        status.setText(getSharedPreferences("probe", MODE_PRIVATE).getString("status", "开启服务后，即可使用悬浮窗。"));
        updateCaptureHint();
    }
    @Override protected void onResume() {
        super.onResume();
        if (deckStore != null && editingDeckId != null) {
            DeckStore.Deck current=deckStore.current();
            // 预览图已不再生成，变更检测只看卡组与名单。
            if (!current.id.equals(editingDeckId) || !current.targets.equals(loadedTargets)) reloadDeckChoices(current.id);
            else updateLineupView(current);
        }
        if (goldReserve != null) goldReserve.setText(String.valueOf(getSharedPreferences("probe", MODE_PRIVATE).getInt("reserve", GoldReserve.DEFAULT)));
        if (captureScheme != null) {
            int index = CaptureScheme.index(CaptureScheme.selected(getSharedPreferences("probe", MODE_PRIVATE)));
            if (captureScheme.getSelectedItemPosition() != index) captureScheme.setSelection(index);
        }
        updateCaptureHint();
        if (autoRefresh != null) autoRefresh.setChecked(getSharedPreferences("probe", MODE_PRIVATE).getBoolean("autoRefresh", false));
        if (championSeason != null && targetCards != null) {
            int set = ChampionCatalog.selectedSet(getSharedPreferences("probe", MODE_PRIVATE));
            if (set != targetCards.catalogSet()) {
                try {
                    targetCards.setCatalog(ChampionCatalog.of(this, set));
                    championSeason.setSelection(seasonIndex(set));
                } catch (RuntimeException e) { Toast.makeText(this, "棋子目录读取失败：" + e.getMessage(), Toast.LENGTH_LONG).show(); }
            }
        }
        handler.post(refresh);
    }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
