package com.piano.waterfall.ui;

import com.piano.waterfall.midi.ScoreCompiler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import javax.sound.midi.Sequence;
import java.util.function.BiConsumer;

/**
 * 可视化简谱实时编译工坊：编辑双轨简谱，选择调号与 BPM，编译成 MIDI Sequence。
 */
public final class JianpuStudioDialog {

    private static String savedRightHand =
            "3+~ 2+~ | 1+~ 7~ | 6~ 5~ | 6~ 7~ |\n"
                    + "1+ 3+ 5+ 3+ | 2+ 7 2+ 5 | 1+ 6 1+ 6 | 5 7 2+ 7 |\n"
                    + "6/ 5/ 6/ 7/ 1+/ 2+/ 3+/ 5+/ | 4+/ 3+/ 2+/ 4+/ 3+/ 2+/ 1+/ 7/ |\n"
                    + "6/ 5/ 4/ 6/ 5/ 4/ 3/ 5/ | 4/ 5/ 6/ 7/ 1+~";

    private static String savedLeftHand =
            "1/ 5/ 1+/ 5/ 5-/ 2/ 5/ 2/ | 6-/ 3/ 6/ 3/ 3-/ 7-/ 3/ 7-/ |\n"
                    + "4-/ 1/ 4/ 1/ 1-/ 5-/ 1/ 5-/ | 4-/ 1/ 4/ 1/ 5-/ 2/ 5/ 2/ |\n"
                    + "1/ 5/ 1+/ 5/ 5-/ 2/ 5/ 2/ | 6-/ 3/ 6/ 3/ 3-/ 7-/ 3/ 7-/ |\n"
                    + "4-/ 1/ 4/ 1/ 1-/ 5-/ 1/ 5-/ | 4-/ 1/ 4/ 1/ 5-/ 2/ 5/ 2/ |\n"
                    + "1/ 5/ 1+/ 5/ 5-/ 2/ 5/ 2/ | 6-/ 3/ 6/ 3/ 3-/ 7-/ 3/ 7-/ |\n"
                    + "4-/ 1/ 4/ 1/ 1-/ 5-/ 1/ 5-/ | 4-/ 1/ 4/ 1/ 1~";

    private JianpuStudioDialog() {
    }

    public static void show(Stage owner, BiConsumer<String, Sequence> onCompiled) {
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("🎼 简谱实时编译工坊");

        VBox root = new VBox(12);
        root.setPadding(new Insets(18));
        root.setStyle("-fx-background-color: #0E121B;");

        TextField titleField = new TextField("Custom Jianpu Live");
        titleField.setPrefWidth(220);
        titleField.setStyle(inputStyle("#00F0FF"));

        ComboBox<String> keySelect = new ComboBox<>();
        keySelect.getItems().addAll(
                "1 = C (60)", "1 = D (62)", "1 = E (64)", "1 = F (65)",
                "1 = G (67)", "1 = A (69)", "1 = B (71)");
        keySelect.getSelectionModel().selectFirst();
        keySelect.setStyle(comboStyle());

        Spinner<Integer> bpmSpinner = new Spinner<>(40, 240, 96, 4);
        bpmSpinner.setEditable(true);
        bpmSpinner.setPrefWidth(92);

        HBox topBar = new HBox(12, label("曲名:"), titleField,
                label("调号:"), keySelect, label("BPM:"), bpmSpinner);
        topBar.setAlignment(Pos.CENTER_LEFT);

        Label syntax = label("语法: 1~7 音符 | 0 休止 | + 高八度 / - 低八度 | "
                + "# 升号 / b 降号 | / 八分音符 | ~ 延长一拍 | . 附点");
        syntax.setStyle("-fx-text-fill: #8C96A8; -fx-font-size: 12px;");

        TextArea rightArea = new TextArea(savedRightHand);
        rightArea.setPrefRowCount(6);
        rightArea.setWrapText(true);
        rightArea.setStyle(textAreaStyle("#00F0FF"));

        TextArea leftArea = new TextArea(savedLeftHand);
        leftArea.setPrefRowCount(7);
        leftArea.setWrapText(true);
        leftArea.setStyle(textAreaStyle("#FF2A85"));

        Button compile = new Button("🚀 立即编译并渲染瀑布流");
        compile.setStyle("-fx-background-color: linear-gradient(to right, #00F0FF, #00FF9D);"
                + "-fx-text-fill: #08090C; -fx-font-weight: bold; -fx-font-size: 13px;"
                + "-fx-padding: 9 20; -fx-background-radius: 8; -fx-cursor: hand;");
        compile.setOnAction(event -> {
            try {
                savedRightHand = rightArea.getText();
                savedLeftHand = leftArea.getText();
                int basePitch = Integer.parseInt(
                        keySelect.getValue().replaceAll("[^0-9]", ""));
                Sequence sequence = ScoreCompiler.compileJianpu(
                        bpmSpinner.getValue(), basePitch, savedRightHand, savedLeftHand);
                onCompiled.accept(titleField.getText(), sequence);
                dialog.close();
            } catch (Exception exception) {
                Alert alert = new Alert(Alert.AlertType.ERROR,
                        "简谱编译错误: " + exception.getMessage());
                alert.setHeaderText("无法编译简谱");
                alert.showAndWait();
            }
        });

        HBox bottom = new HBox(compile);
        bottom.setAlignment(Pos.CENTER_RIGHT);
        VBox.setVgrow(rightArea, Priority.ALWAYS);
        VBox.setVgrow(leftArea, Priority.ALWAYS);

        root.getChildren().addAll(topBar, syntax,
                label("右手主旋律 · 青色轨道"), rightArea,
                label("左手伴奏 / 和弦 · 洋红轨道"), leftArea, bottom);

        Scene scene = new Scene(root, 820, 620);
        scene.setFill(javafx.scene.paint.Color.web("#0E121B"));
        dialog.setScene(scene);
        dialog.show();
    }

    private static Label label(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: #DCE2EC; -fx-font-weight: bold; -fx-font-size: 12px;");
        return label;
    }

    private static String inputStyle(String accent) {
        return "-fx-background-color: #141B2A; -fx-text-fill: " + accent + ";"
                + "-fx-border-color: #2A344B; -fx-border-radius: 6; -fx-background-radius: 6;"
                + "-fx-padding: 6 9;";
    }

    private static String comboStyle() {
        return "-fx-background-color: #141B2A; -fx-text-fill: #EAF0F8;"
                + "-fx-border-color: #2A344B; -fx-border-radius: 6; -fx-background-radius: 6;";
    }

    private static String textAreaStyle(String accent) {
        return "-fx-control-inner-background: #111724; -fx-text-fill: " + accent + ";"
                + "-fx-font-family: 'Cascadia Mono', Consolas, monospace; -fx-font-size: 13px;"
                + "-fx-border-color: #222C40; -fx-border-radius: 6; -fx-background-radius: 6;";
    }
}
