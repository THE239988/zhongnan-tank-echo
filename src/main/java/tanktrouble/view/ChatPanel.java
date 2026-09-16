package tanktrouble.view;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import tanktrouble.net.Protocol.Chat;

/** Compact room chat shared by the lobby and the multiplayer battle shell. */
final class ChatPanel extends VBox {
    private static final int COMPACT_LINES=6;

    private final VBox log=new VBox(2);
    private final TextField composer=new TextField();
    private final Button send=new Button("发送");
    private final Consumer<String> onSend;
    private final List<String> rendered=new ArrayList<>();
    private Node focusReturn;

    ChatPanel(Consumer<String> onSend,double width) {
        this.onSend=onSend;
        setId("chat-panel");
        setSpacing(6);
        setPrefWidth(width);
        setMaxWidth(width);
        setFocusTraversable(true);
        setOnMouseClicked(event->composer.requestFocus());

        Label heading=new Label("房间聊天");
        heading.getStyleClass().add("eyebrow");
        log.setId("chat-log");
        log.setFillWidth(true);
        log.setPadding(new Insets(6,8,6,8));
        log.getStyleClass().add("chat-log");

        composer.setId("chat-input");
        composer.setPromptText("输入消息，回车发送");
        composer.setEditable(true);
        composer.setDisable(false);
        composer.setFocusTraversable(true);
        composer.setPrefHeight(42);
        composer.setOnAction(event->submit());
        composer.addEventFilter(KeyEvent.KEY_PRESSED,event->{
            if(event.getCode()==javafx.scene.input.KeyCode.ENTER) {
                submit();event.consume();
            }
        });
        send.setId("chat-send");
        send.setFocusTraversable(false);
        send.setOnAction(event->submit());
        HBox composerRow=new HBox(6,composer,send);
        HBox.setHgrow(composer,Priority.ALWAYS);

        getChildren().addAll(heading,log,composerRow);
        setMinHeight(230);
        setPrefHeight(290);
        setMaxHeight(Double.MAX_VALUE);
        log.setMinHeight(90);
        log.setPrefHeight(130);
        VBox.setVgrow(log,Priority.NEVER);
    }

    private void submit() {
        String text=composer.getText();
        if(text==null||text.isBlank()) return;
        onSend.accept(text.strip());
        composer.clear();
        composer.requestFocus();
    }

    void focusComposer() {
        Node current=getScene()==null?null:getScene().getFocusOwner();
        if(current!=null&&current!=composer) focusReturn=current;
        composer.setDisable(false);
        javafx.application.Platform.runLater(composer::requestFocus);
    }

    void releaseComposer() {
        Node target=focusReturn;
        focusReturn=null;
        if(target!=null&&target.getScene()!=null) target.requestFocus();
        else requestFocus();
    }

    boolean isComposing() {return composer.isFocused();}

    void apply(List<Chat> lines) {
        int from=Math.max(0,lines.size()-COMPACT_LINES);
        boolean unchanged=rendered.size()==lines.size()-from;
        if(unchanged) {
            for(int i=from;i<lines.size()&&unchanged;i++)
                if(!format(lines.get(i)).equals(rendered.get(i-from))) unchanged=false;
        }
        if(unchanged) return;

        rendered.clear();
        log.getChildren().clear();
        for(int i=from;i<lines.size();i++) {
            Chat line=lines.get(i);
            String text=format(line);
            rendered.add(text);
            Label row=new Label(text);
            row.setWrapText(true);
            row.setMaxWidth(Double.MAX_VALUE);
            row.getStyleClass().add(line.isSystem()?"chat-system":"chat-say");
            log.getChildren().add(row);
        }
    }

    private static String format(Chat line) {
        return line.isSystem()?line.text():line.name()+"：" + line.text();
    }
}
