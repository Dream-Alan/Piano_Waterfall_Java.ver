package com.piano.waterfall;

import com.piano.waterfall.export.VideoExporter;
import com.piano.waterfall.midi.AudioEngine;
import com.piano.waterfall.midi.AudioTranscriber;
import com.piano.waterfall.midi.MidiParser;
import com.piano.waterfall.midi.PianoVoice;
import com.piano.waterfall.midi.SampledPianoEngine;
import com.piano.waterfall.midi.ScoreCompiler;
import com.piano.waterfall.model.KeyGeometry;
import com.piano.waterfall.model.NoteBlock;
import com.piano.waterfall.render.WaterfallRenderer;
import com.piano.waterfall.ui.JianpuStudioDialog;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Slider;
import javafx.collections.FXCollections;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * JavaFX 主窗口：Canvas 渲染循环 + MIDI/原曲播放 + 悬浮玻璃控制坞。
 */
public class App extends Application {

    private static final double INIT_WIDTH = 1440;
    private static final double INIT_HEIGHT = 820;
    private static final double KEYBOARD_WIDTH_RATIO = 0.94;

    private Stage stage;
    private Canvas canvas;
    private GraphicsContext graphics;
    private StackPane root;
    private KeyGeometry[] keys;
    private List<NoteBlock> notes = List.of();
    private Sequence sequence;
    private String songTitle = "Piano Waterfall";
    private File soundFont;
    private File currentAudio;

    private final WaterfallRenderer renderer = new WaterfallRenderer();
    private final AudioEngine audioEngine = new AudioEngine();
    private AnimationTimer renderLoop;
    private boolean exporting;
    private boolean exitAfterExport;
    private boolean snapshotMode;
    private double lastMouseX = Double.NaN;
    private double lastMouseY = Double.NaN;

    private VBox dock;
    private Slider timeline;
    private Label timeLabel;
    private Button playButton;
    private Button themeButton;
    private Button audioButton;
    private Button aiButton;
    private ComboBox<PianoVoice> voiceCombo;
    private int sampleRenderGeneration;
    private PauseTransition idleTimer;
    private FadeTransition fadeOut;
    private boolean draggingTimeline;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        this.soundFont = findSoundFont();
        loadWindowIcon();

        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        double scale = Screen.getPrimary().getOutputScaleX();
        double availableWidth = bounds.getWidth();
        double availableHeight = bounds.getHeight();
        try {
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            if (scale > 1.0 && Math.abs(availableWidth - screen.width) < 2) {
                availableWidth = screen.width / scale;
                availableHeight = screen.height / scale;
            }
        } catch (Exception ignored) {
            // 无法读取 AWT 尺寸时沿用 JavaFX 的可视区域。
        }
        double width = Math.min(INIT_WIDTH, availableWidth * 0.88);
        double height = Math.min(INIT_HEIGHT, availableHeight * 0.80);
        String forcedSize = System.getProperty("piano.size");
        if (forcedSize != null && forcedSize.contains("x")) {
            String[] parts = forcedSize.split("x");
            width = Double.parseDouble(parts[0]);
            height = Double.parseDouble(parts[1]);
        }

        canvas = new Canvas(width, height);
        graphics = canvas.getGraphicsContext2D();

        dock = buildFloatingDock();
        StackPane.setAlignment(dock, Pos.BOTTOM_CENTER);
        StackPane.setMargin(dock, new Insets(0, 40, 200, 40));

        root = new StackPane(canvas, dock);
        root.setStyle("-fx-background-color: #07090E;");
        Scene scene = new Scene(root, width, height, Color.web("#07090E"));
        var appCss = App.class.getResource("/voice.css");
        if (appCss != null) {
            scene.getStylesheets().add(appCss.toExternalForm());
        }

        canvas.widthProperty().bind(scene.widthProperty());
        canvas.heightProperty().bind(scene.heightProperty());
        canvas.widthProperty().addListener((obs, oldValue, newValue) ->
                rebuildKeys(newValue.doubleValue()));
        canvas.heightProperty().addListener((obs, oldValue, newValue) ->
                rebuildKeys(canvas.getWidth()));

        keys = KeyGeometry.build88Keys(width, KEYBOARD_WIDTH_RATIO);

        File initial = initialMidiFile();
        if (initial != null) {
            try {
                loadSequence(MidiSystem.getSequence(initial), initial.getName(), null);
            } catch (Exception exception) {
                showError("无法打开 MIDI", exception);
                loadBuiltInDemo();
            }
        } else {
            loadBuiltInDemo();
        }

        scene.setOnKeyPressed(this::handleKey);
        scene.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            }
            event.consume();
        });
        scene.setOnDragDropped(event -> {
            if (event.getDragboard().hasFiles()) {
                File file = event.getDragboard().getFiles().get(0);
                handleDroppedFile(file);
            }
            event.setDropCompleted(true);
            event.consume();
        });

        renderLoop = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (exporting) {
                    return;
                }
                double currentTime = audioEngine.getCurrentTimeSec();
                renderer.render(graphics, canvas.getWidth(), canvas.getHeight(),
                        currentTime, notes, keys);
                updateDock(currentTime);
            }
        };
        renderLoop.start();

        String audioProp = System.getProperty("piano.audio");
        if (audioProp != null && !audioProp.isBlank()) {
            File audioFile = new File(audioProp);
            if (audioFile.isFile()) {
                audioEngine.attachExternalAudio(audioFile, true);
                refreshAudioButton();
            }
        }

        String transcribeProp = System.getProperty("piano.transcribe");
        if (transcribeProp != null && !transcribeProp.isBlank()) {
            File transcribeFile = new File(transcribeProp);
            if (transcribeFile.isFile()) {
                Platform.runLater(() -> transcribeAudio(transcribeFile));
            }
        }

        setupAutoHideDock(scene);
        stage.setTitle("Piano Waterfall Java  |  " + songTitle);
        stage.setMinWidth(980);
        stage.setMinHeight(620);
        stage.setScene(scene);
        stage.show();
        stage.centerOnScreen();

        String exportPath = System.getProperty("piano.export");
        if (exportPath != null) {
            exitAfterExport = true;
            PauseTransition delay = new PauseTransition(Duration.seconds(2));
            delay.setOnFinished(event -> startExport(new File(exportPath)));
            delay.play();
        }

        String snapshotPath = System.getProperty("piano.snapshot");
        if (snapshotPath != null) {
            snapshotMode = true;
            showDockPermanently();
            PauseTransition pause = new PauseTransition(Duration.seconds(11));
            pause.setOnFinished(event -> audioEngine.togglePause());
            pause.play();
            PauseTransition delay = new PauseTransition(Duration.seconds(12));
            delay.setOnFinished(event -> {
                saveScreenshot(Path.of(snapshotPath));
                Platform.exit();
            });
            delay.play();
        }
    }

    private VBox buildFloatingDock() {
        VBox dockBox = new VBox(9);
        dockBox.setMaxWidth(1240);
        dockBox.setMaxHeight(Region.USE_PREF_SIZE);
        dockBox.setMinHeight(Region.USE_PREF_SIZE);
        dockBox.setPadding(new Insets(11, 18, 11, 18));
        dockBox.setStyle("-fx-background-color: rgba(13, 18, 28, 0.88);"
                + "-fx-background-radius: 14;"
                + "-fx-border-color: rgba(0, 240, 255, 0.36);"
                + "-fx-border-width: 1.1;"
                + "-fx-border-radius: 14;"
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.78), 22, 0, 0, 7);");

        timeline = new Slider(0, 100, 0);
        timeline.setId("timelineSlider");
        timeline.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(timeline, Priority.ALWAYS);
        timeline.setStyle(neonSliderStyle("#00F0FF"));
        timeline.setOnMousePressed(event -> draggingTimeline = true);
        timeline.setOnMouseReleased(event -> {
            audioEngine.seekToSeconds(timeline.getValue());
            draggingTimeline = false;
        });

        timeLabel = new Label("00:00 / 00:00");
        timeLabel.setStyle("-fx-text-fill: #8FA3BA; -fx-font-family: Consolas; -fx-font-size: 11px;");
        timeLabel.setMinWidth(98);

        HBox progressRow = new HBox(10, timeline, timeLabel);
        progressRow.setAlignment(Pos.CENTER);

        playButton = neonButton("⏸ 暂停", "#00F0FF");
        playButton.setOnAction(event -> {
            audioEngine.togglePause();
            refreshPlayButton();
        });
        Button restartButton = neonButton("⏮ 重播", "#DCE2EC");
        restartButton.setOnAction(event -> {
            audioEngine.restart();
            renderer.setSongMetadata(songTitle, notes);
        });
        Button midiButton = neonButton("📂 MIDI", "#DCE2EC");
        midiButton.setOnAction(event -> chooseMidiFile());
        Button jianpuButton = neonButton("🎼 简谱工坊", "#00FF9D");
        jianpuButton.setOnAction(event -> JianpuStudioDialog.show(stage,
                (title, seq) -> loadSequenceSafe(seq, title, null)));
        aiButton = neonButton("🤖 音频转 MIDI", "#FF2A85");
        aiButton.setOnAction(event -> chooseAudioForTranscription());
        MenuItem pythonItem = new MenuItem("设置 Python 解释器…");
        pythonItem.setOnAction(event -> promptPython());
        MenuItem projectItem = new MenuItem("设置 piano_waterfall 项目目录…");
        projectItem.setOnAction(event -> promptPythonProject());
        aiButton.setContextMenu(new ContextMenu(pythonItem, projectItem));
        audioButton = neonButton("🎧 原曲", "#FFB800");
        audioButton.setOnAction(event -> toggleExternalAudio());
        themeButton = neonButton("🎨 赛博青粉", "#DCE2EC");
        themeButton.setOnAction(event -> {
            renderer.nextTheme();
            refreshThemeButton();
            showDock();
        });
        Button labelButton = neonButton("🔤 标注", "#9AD7FF");
        labelButton.setOnAction(event -> {
            renderer.toggleNoteLabels();
            showDock();
        });
        Button exportButton = neonButton("🎥 导出 MP4", "#FF75B5");
        exportButton.setOnAction(event -> exportVideo());

        Label speedLabel = new Label("速度");
        speedLabel.setStyle("-fx-text-fill: #8FA3BA; -fx-font-size: 11px;");
        Slider speedSlider = new Slider(140, 620, renderer.getFallSpeed());
        speedSlider.setPrefWidth(96);
        speedSlider.setStyle(neonSliderStyle("#00F0FF"));
        speedSlider.valueProperty().addListener((obs, oldValue, newValue) ->
                renderer.setFallSpeed(newValue.doubleValue()));

        Label voiceLabel = new Label("♪ 音色");
        voiceLabel.setStyle("-fx-text-fill: #00F0FF; -fx-font-size: 12px; -fx-font-weight: bold;");
        voiceCombo = new ComboBox<>(FXCollections.observableArrayList(PianoVoice.values()));
        voiceCombo.setValue(PianoVoice.GRAND);
        voiceCombo.setPrefWidth(172);
        voiceCombo.setVisibleRowCount(4);
        voiceCombo.setPrefHeight(32);
        voiceCombo.setStyle("-fx-background-color: #0E2230; -fx-text-fill: #EAF6FF;"
                + " -fx-border-color: #00F0FF; -fx-border-width: 1.4;"
                + " -fx-border-radius: 15; -fx-background-radius: 15;"
                + " -fx-font-size: 13px; -fx-font-weight: bold; -fx-padding: 2 8 2 10;");
        var voiceCss = App.class.getResource("/voice.css");
        if (voiceCss != null) {
            voiceCombo.getStylesheets().add(voiceCss.toExternalForm());
        }
        voiceCombo.setButtonCell(voiceCell(true));
        voiceCombo.setCellFactory(list -> voiceCell(false));
        voiceCombo.setTooltip(new Tooltip("切换钢琴音色（后台采样渲染）"));
        voiceCombo.setOnAction(event -> renderSampledPiano());

        FlowPane controls = new FlowPane(8, 7);
        controls.setAlignment(Pos.CENTER);
        controls.getChildren().addAll(playButton, restartButton, midiButton, jianpuButton,
                aiButton, audioButton, themeButton, labelButton, exportButton,
                speedLabel, speedSlider);

        Label hintLabel = new Label("T 主题 · V/L 音色/标注 · M 静音MIDI · J 简谱工坊 · 右键 AI 设置 Python · E 导出");
        hintLabel.setStyle("-fx-text-fill: #5E7188; -fx-font-size: 10px;");

        Region footerSpacer = new Region();
        HBox footer = new HBox(8, voiceLabel, voiceCombo, footerSpacer, hintLabel);
        footer.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);

        dockBox.getChildren().addAll(progressRow, controls, footer);
        dockBox.setOnMouseEntered(event -> {
            if (idleTimer != null) {
                idleTimer.stop();
            }
            dockBox.setOpacity(1.0);
        });
        dockBox.setOnMouseExited(event -> {
            if (idleTimer != null) {
                idleTimer.playFromStart();
            }
        });
        return dockBox;
    }

    private void setupAutoHideDock(Scene scene) {
        fadeOut = new FadeTransition(Duration.millis(320), dock);
        fadeOut.setToValue(0.0);
        idleTimer = new PauseTransition(Duration.seconds(1.6));
        idleTimer.setOnFinished(event -> {
            if (!dock.isHover() && !snapshotMode) {
                fadeOut.playFromStart();
            }
        });
        scene.setOnMouseMoved(this::handleMouseMoved);
        idleTimer.playFromStart();
    }

    private ListCell<PianoVoice> voiceCell(boolean withArrow) {
        return new ListCell<>() {
            @Override
            protected void updateItem(PianoVoice item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                Label label = new Label(empty || item == null
                        ? "" : item.label() + (withArrow ? "  ▾" : ""));
                label.setStyle("-fx-text-fill: #F2FBFF; -fx-font-size: 13px;"
                        + " -fx-font-weight: bold; -fx-background-color: transparent;");
                setGraphic(label);
            }
        };
    }

    private void handleMouseMoved(MouseEvent event) {
        double x = event.getSceneX();
        double y = event.getSceneY();
        if (Double.isNaN(lastMouseX)
                || Math.abs(x - lastMouseX) > 2.0
                || Math.abs(y - lastMouseY) > 2.0) {
            lastMouseX = x;
            lastMouseY = y;
            showDock();
        }
    }

    private void showDock() {
        if (dock == null) {
            return;
        }
        if (fadeOut != null) {
            fadeOut.stop();
        }
        dock.setOpacity(1.0);
        if (idleTimer != null) {
            idleTimer.playFromStart();
        }
    }

    private void showDockPermanently() {
        if (fadeOut != null) {
            fadeOut.stop();
        }
        if (idleTimer != null) {
            idleTimer.stop();
        }
        dock.setOpacity(1.0);
    }

    private void updateDock(double currentTime) {
        if (timeline == null) {
            return;
        }
        double duration = Math.max(1.0, renderer.getTotalDurationSec());
        timeline.setMax(duration);
        if (!draggingTimeline && currentTime >= 0) {
            timeline.setValue(Math.min(duration, currentTime));
        }
        timeLabel.setText(formatTime(Math.max(0, currentTime)) + " / " + formatTime(duration));
        refreshPlayButton();
    }

    private void refreshPlayButton() {
        if (playButton != null) {
            String text = audioEngine.isPaused() ? "▶ 播放" : "⏸ 暂停";
            if (!text.equals(playButton.getText())) {
                playButton.setText(text);
            }
        }
    }

    private void refreshThemeButton() {
        if (themeButton != null) {
            themeButton.setText("🎨 " + renderer.getTheme().label);
        }
    }

    private void refreshAudioButton() {
        if (audioButton != null) {
            audioButton.setText(audioEngine.hasExternalAudio() ? "🎧 移除原曲" : "🎧 原曲");
        }
    }

    private Button neonButton(String text, String color) {
        Button button = new Button(text);
        button.setStyle("-fx-background-color: #151D2C; -fx-text-fill: " + color + ";"
                + "-fx-font-weight: bold; -fx-font-size: 11px; -fx-background-radius: 7;"
                + "-fx-border-color: " + color + "; -fx-border-radius: 7;"
                + "-fx-border-width: 0.75; -fx-padding: 5 10; -fx-cursor: hand;");
        return button;
    }

    private String neonSliderStyle(String color) {
        return "-fx-control-inner-background: #151D2C; -fx-accent: " + color + ";";
    }

    /** 保存当前界面，便于文档截图和渲染验证。 */
    private void saveScreenshot(Path path) {
        try {
            WritableImage image = root.snapshot(null, null);
            int w = (int) image.getWidth();
            int h = (int) image.getHeight();
            int[] pixels = new int[w * h];
            image.getPixelReader().getPixels(0, 0, w, h,
                    PixelFormat.getIntArgbInstance(), pixels, 0, w);
            BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            buffered.setRGB(0, 0, w, h, pixels, 0, w);
            ImageIO.write(buffered, "png", path.toFile());
            System.out.println("SNAPSHOT " + path.toAbsolutePath());
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    private void rebuildKeys(double width) {
        keys = KeyGeometry.build88Keys(width, KEYBOARD_WIDTH_RATIO);
    }

    private void loadSequenceSafe(Sequence seq, String title, File companionAudio) {
        try {
            loadSequence(seq, title, companionAudio);
        } catch (Exception exception) {
            showError("无法加载曲目", exception);
        }
    }

    private void loadBuiltInDemo() {
        try {
            loadSequence(ScoreCompiler.createBuiltInJianpuDemo(),
                    "Canon in D (Built-in Jianpu)", null);
        } catch (Exception exception) {
            showError("内置曲目初始化失败", exception);
        }
    }

    private void loadSequence(Sequence newSequence, String title, File companionAudio)
            throws Exception {
        this.sequence = newSequence;
        this.songTitle = title;
        this.currentAudio = companionAudio;
        this.notes = MidiParser.parseSequence(newSequence);
        this.renderer.setSongMetadata(title, notes);
        this.audioEngine.stop();
        this.audioEngine.init(newSequence, soundFont);
        if (companionAudio != null) {
            this.audioEngine.attachExternalAudio(companionAudio, true);
        }
        this.audioEngine.start();
        refreshAudioButton();
        refreshPlayButton();
        if (stage != null) {
            stage.setTitle("Piano Waterfall Java  |  " + title);
        }
        renderSampledPiano();
    }

    private void renderSampledPiano() {
        if (sequence == null || notes == null || notes.isEmpty() || currentAudio != null) {
            return;
        }
        PianoVoice voice = voiceCombo == null ? PianoVoice.GRAND : voiceCombo.getValue();
        if (voice == null) {
            voice = PianoVoice.GRAND;
        }
        final PianoVoice selectedVoice = voice;
        final String cacheKey = songTitle + "|" + sequence.getMicrosecondLength()
                + "|" + notes.size();
        final int generation = ++sampleRenderGeneration;
        double resume = audioEngine.getCurrentTimeSec();
        Thread worker = new Thread(() -> {
            try {
                File wav = SampledPianoEngine.render(cacheKey, notes, selectedVoice,
                        percent -> Platform.runLater(() -> {
                            if (generation == sampleRenderGeneration && stage != null) {
                                stage.setTitle(String.format("采样钢琴 · %s · %d%%  |  %s",
                                        selectedVoice.label(), Math.round(percent), songTitle));
                            }
                        }));
                Platform.runLater(() -> {
                    if (generation != sampleRenderGeneration) {
                        return;
                    }
                    audioEngine.attachSampledPiano(wav);
                    audioEngine.seekToSeconds(Math.max(0.0, resume));
                    if (stage != null) {
                        stage.setTitle("Piano Waterfall Java  |  " + songTitle);
                    }
                });
            } catch (Exception exception) {
                Platform.runLater(() -> {
                    if (generation == sampleRenderGeneration && stage != null) {
                        stage.setTitle("Piano Waterfall Java  |  " + songTitle);
                    }
                    System.err.println("采样钢琴渲染失败: " + exception.getMessage());
                });
            }
        }, "sample-piano-render");
        worker.setDaemon(true);
        worker.start();
    }

    private void handleKey(KeyEvent event) {
        if (exporting) {
            return;
        }
        showDock();
        switch (event.getCode()) {
            case SPACE -> {
                audioEngine.togglePause();
                refreshPlayButton();
            }
            case LEFT -> audioEngine.seekRelative(-5.0);
            case RIGHT -> audioEngine.seekRelative(5.0);
            case R -> {
                audioEngine.restart();
                renderer.setSongMetadata(songTitle, notes);
            }
            case T -> {
                renderer.nextTheme();
                refreshThemeButton();
            }
            case L -> renderer.toggleNoteLabels();
            case V -> voiceCombo.getSelectionModel().selectNext();
            case M -> audioEngine.toggleMidiMute();
            case O -> chooseMidiFile();
            case J -> JianpuStudioDialog.show(stage,
                    (title, seq) -> loadSequenceSafe(seq, title, null));
            case E -> exportVideo();
            case F11 -> stage.setFullScreen(!stage.isFullScreen());
            default -> {
                // 其他按键不处理。
            }
        }
        event.consume();
    }

    private void handleDroppedFile(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mid") || name.endsWith(".midi")) {
            openMidi(file);
        } else if (name.endsWith(".mp3") || name.endsWith(".wav")
                || name.endsWith(".flac") || name.endsWith(".ogg")) {
            transcribeAudio(file);
        }
    }

    private void chooseMidiFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 MIDI 文件");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("MIDI 文件", "*.mid", "*.midi"));
        File selected = chooser.showOpenDialog(stage);
        if (selected != null) {
            openMidi(selected);
        }
    }

    private void openMidi(File file) {
        try {
            Sequence loaded = MidiSystem.getSequence(file);
            loadSequence(loaded, file.getName(), null);
        } catch (Exception exception) {
            showError("无法打开 MIDI 文件", exception);
        }
    }

    private void chooseAudioForTranscription() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要 AI 扒谱的音频");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "音频文件", "*.mp3", "*.wav", "*.flac", "*.ogg"));
        File selected = chooser.showOpenDialog(stage);
        if (selected != null) {
            transcribeAudio(selected);
        }
    }

    private void promptPython() {
        TextInputDialog dialog = new TextInputDialog(AudioTranscriber.currentPython());
        dialog.setTitle("AI 转录设置");
        dialog.setHeaderText("Python 解释器");
        dialog.setContentText("路径（需安装 piano-waterfall 或设置项目目录）：");
        dialog.showAndWait().ifPresent(value -> {
            AudioTranscriber.setPythonExecutable(value);
            showDock();
        });
    }

    private void promptPythonProject() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择 piano_waterfall 项目根目录");
        File initial = new File(AudioTranscriber.currentPythonProject());
        if (initial.isDirectory()) {
            chooser.setInitialDirectory(initial);
        }
        File selected = chooser.showDialog(stage);
        if (selected != null) {
            AudioTranscriber.setPythonProject(selected.getAbsolutePath());
            showDock();
        }
    }

    private void transcribeAudio(File audioFile) {
        aiButton.setText("🤖 扒谱中…");
        aiButton.setDisable(true);
        AudioTranscriber.transcribeAudioAsync(audioFile,
                message -> stage.setTitle("AI 扒谱  |  " + message),
                midi -> {
                    aiButton.setText("🤖 音频转 MIDI");
                    aiButton.setDisable(false);
                    try {
                        Sequence loaded = MidiSystem.getSequence(midi);
                        loadSequence(loaded, audioFile.getName() + " [AI]", audioFile);
                        audioEngine.togglePause();
                        refreshPlayButton();
                        showDock();
                    } catch (Exception exception) {
                        showError("AI 扒谱完成但无法加载 MIDI", exception);
                    }
                },
                message -> {
                    aiButton.setText("🤖 音频转 MIDI");
                    aiButton.setDisable(false);
                    Alert alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK);
                    alert.setHeaderText("AI 扒谱失败");
                    alert.showAndWait();
                });
    }

    private void toggleExternalAudio() {
        if (audioEngine.hasExternalAudio()) {
            audioEngine.attachExternalAudio(null, false);
            audioEngine.restart();
            currentAudio = null;
            refreshAudioButton();
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要与 MIDI 同步播放的原曲");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "音频文件", "*.mp3", "*.wav", "*.flac", "*.ogg"));
        File selected = chooser.showOpenDialog(stage);
        if (selected != null) {
            currentAudio = selected;
            audioEngine.attachExternalAudio(selected, true);
            audioEngine.restart();
            refreshAudioButton();
        }
    }

    private void exportVideo() {
        if (exporting || sequence == null) {
            return;
        }
        FileChooser saver = new FileChooser();
        saver.setTitle("导出 60 FPS MP4");
        saver.setInitialFileName("piano_waterfall_60fps.mp4");
        saver.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("MP4 视频", "*.mp4"));
        File output = saver.showSaveDialog(stage);
        if (output != null) {
            startExport(output);
        }
    }

    private void startExport(File output) {
        if (exporting || sequence == null) {
            return;
        }
        exporting = true;
        if (renderLoop != null) {
            renderLoop.stop();
        }
        audioEngine.stop();
        renderer.setSongMetadata(songTitle, notes);

        final Sequence exportedSequence = sequence;
        final String exportedTitle = songTitle;
        VideoExporter.exportMp4Async(stage, canvas, renderer, exportedSequence, notes,
                soundFont, output, () -> {
                    exporting = false;
                    try {
                        loadSequence(exportedSequence, exportedTitle, currentAudio);
                    } catch (Exception exception) {
                        showError("导出完成，但恢复播放失败", exception);
                    }
                    if (renderLoop != null) {
                        renderLoop.start();
                    }
                    if (exitAfterExport) {
                        Platform.exit();
                    }
                });
    }

    private File initialMidiFile() {
        List<String> parameters = getParameters().getRaw();
        if (!parameters.isEmpty()) {
            File candidate = new File(parameters.get(0));
            if (candidate.isFile()) {
                return candidate;
            }
        }
        File demo = new File("midi/demo.mid");
        return demo.isFile() ? demo : null;
    }

    private File findSoundFont() {
        File[] candidates = {
                new File("soundfont/piano.sf2"),
                new File("src/main/resources/soundfont/piano.sf2")
        };
        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    private void loadWindowIcon() {
        try (InputStream icon = App.class.getResourceAsStream("/icon.png")) {
            if (icon != null) {
                stage.getIcons().add(new Image(icon));
            }
        } catch (Exception ignored) {
            // 图标缺失不影响运行。
        }
    }

    private void showError(String header, Exception exception) {
        exception.printStackTrace();
        Alert alert = new Alert(Alert.AlertType.ERROR,
                exception.getMessage() == null ? exception.toString() : exception.getMessage(),
                ButtonType.OK);
        alert.setHeaderText(header);
        alert.setTitle("Piano Waterfall");
        alert.showAndWait();
    }

    private String formatTime(double seconds) {
        int total = (int) Math.max(0, seconds);
        return String.format("%02d:%02d", total / 60, total % 60);
    }

    @Override
    public void stop() {
        if (renderLoop != null) {
            renderLoop.stop();
        }
        audioEngine.stop();
        Platform.exit();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
