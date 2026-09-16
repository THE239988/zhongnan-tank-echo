package tanktrouble.view;

import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.Event;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 双人对战的两块面板必须在小窗口里也能一眼看全 —— 它们存在的全部理由就是把信息摆在两名玩家
 * 各自的面前收窄到需要滚动，就等于没做到。
 *
 * <p>用测量而不是看图来判断：面板内容比视口高，就是出现竖向滚动条的充要条件；而滚动条在截图里
 * 只是 10px 的一条细边，肉眼很容易看漏。PNG 照样会出，但那是用来判断"好不好看"的。
 *
 * <p>刻意独立于 {@link FxSmokeTest}：那条流程在一个更早的位置就失败了（Esc 关闭排行榜之后找不到
 * {@code #battle-page}），任何挂在它后面的断言都不会被执行 —— 挂过去只是制造"有覆盖"的假象。
 *
 * <p>单独运行：
 * {@code mvn -B test -Dtest=DuelPanelLayoutProbeTest -DfxProbe=true -Dtanktrouble.probeDir=target/probe}
 */
@EnabledIfSystemProperty(named="fxProbe",matches="true")
class DuelPanelLayoutProbeTest {
    /**
     * 双人面板只在场景宽度达到这个值时才显示 —— 更窄的窗口走 {@code duelCompact} 那条紧凑 HUD 路径，
     * 两块面板整个隐藏。所以能测量它们的下限是这个阈值，而不是 {@code stage.setMinWidth}：
     * 在 980×700 下量到的是一块被 {@code setManaged(false)} 摘掉的节点，量什么都不作数。
     */
    private static final double DUEL_PANELS_MIN_SCENE_WIDTH=1450;
    /** 默认窗口尺寸。面板在这里必定可见，也正是玩家平时看到的尺寸。 */
    private static final double DEFAULT_WINDOW_WIDTH=1524;
    private static final double DEFAULT_WINDOW_HEIGHT=860;

    @SuppressWarnings("unchecked") private static <T> T get(Object object,String name) throws Exception {
        Field field=object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T)field.get(object);
    }

    @BeforeAll static void startToolkit() throws Exception {
        CompletableFuture<Void> ready=new CompletableFuture<>();
        try {Platform.startup(()->ready.complete(null));}
        catch(IllegalStateException alreadyRunning) {return;}
        ready.get(15,TimeUnit.SECONDS);
    }

    @AfterAll static void stopToolkit() {Platform.exit();}

    @Test void duelPanelsFitWithoutScrollingAtTheSmallestWindow() throws Exception {
        CompletableFuture<Void> done=new CompletableFuture<>();
        Platform.runLater(()->{
            TankTroubleApp app=new TankTroubleApp();
            Stage stage=new Stage();
            try {
                app.start(stage);
                // 存档里的「全屏」一旦为真，stage.setWidth 会被直接忽略 —— 探针就永远拿不到小窗口，
                // 而量出来的会是全屏尺寸。这里显式退出全屏，配合 -Dtanktrouble.dataDir 用独立存档。
                stage.setFullScreen(false);
                DuelPanelLayoutProbeTest.<AnimationTimer>get(app,"timer").stop();
                DuelPanelLayoutProbeTest.<HomePage>get(app,"homePage").cancelOpening();
                Scene scene=stage.getScene();
                click(scene,"#home-start");
                click(scene,"#mode-DUEL");
                stage.setWidth(DEFAULT_WINDOW_WIDTH);
                stage.setHeight(DEFAULT_WINDOW_HEIGHT);
                // 原生缩放是异步的：不等它传到 scene 上，量到的还是缩放前的尺寸。
                PauseTransition resized=new PauseTransition(Duration.millis(400));
                resized.setOnFinished(event->{
                    try {
                        assertTrue(scene.getWidth()<DEFAULT_WINDOW_WIDTH,
                                "窗口没调到 "+DEFAULT_WINDOW_WIDTH+"：scene 宽 "+scene.getWidth()
                                        +"（全屏未退出？存档里 fullscreen 为真？屏幕比窗口小？）");
                        assertTrue(scene.getWidth()>=DUEL_PANELS_MIN_SCENE_WIDTH,
                                "场景宽 "+scene.getWidth()+" 低于 "+DUEL_PANELS_MIN_SCENE_WIDTH
                                        +"，双人面板此时是隐藏的，测不到任何东西");
                        shot(app,stage,1.0,"prep-duel");
                        // 用 layoutBounds 而不是 boundsInLocal：后者包含 dropshadow 效果，
                        // 会把带阴影那一栏量得虚高，看着像两栏不等高。
                        for(String id:List.of("#tank-select-0","#character-select-0")) {
                            Node column=scene.lookup(id).getParent();
                            var b=column.localToScene(column.getLayoutBounds());
                            System.out.printf("COL %s top=%.1f bottom=%.1f h=%.1f%n",
                                    id,b.getMinY(),b.getMaxY(),b.getHeight());
                        }
                        click(scene,"#start-button");
                        assertNotNull(scene.lookup("#player-panel-1"),"应已进入双人对战");
                    } catch(Throwable failed) {
                        done.completeExceptionally(failed);app.stop();stage.close();return;
                    }
                    // 进入战斗页会重建场景内容，再放一拍让布局真正跑完：抢在布局前量，视口会是半成品尺寸。
                    PauseTransition laid=new PauseTransition(Duration.millis(400));
                    laid.setOnFinished(ignored->{
                        try {
                            scene.getRoot().applyCss();scene.getRoot().layout();
                            for(int seat=1;seat<=2;seat++) {
                                BorderPane panel=(BorderPane)scene.lookup("#player-panel-"+seat);
                                ScrollPane scroll=(ScrollPane)panel.getCenter();
                                assertFitsVertically(scene,scroll,"P"+seat+" 面板");
                                assertNoVerticalScrollBar(scroll,"P"+seat+" 面板");
                                Node portrait=panel.lookup("#portrait-view");
                                System.out.printf("P%d 立绘实际高 %.1f（下限 %.0f）%n",
                                        seat,portrait.getBoundsInLocal().getHeight(),ImageFloor.VALUE);
                                for(String id:List.of("#tactical-radar-"+seat,"#pulse-button-"+seat)) {
                                    Node node=scene.lookup(id);
                                    assertNotNull(node,id);
                                    assertTrue(node.localToScene(node.getBoundsInLocal()).getMaxY()<=scene.getHeight(),
                                            id+" 超出窗口底边（scene 高 "+scene.getHeight()+"）");
                                }
                            }
                            shot(app,stage,1.0,"duel-panels-default");
                            done.complete(null);
                        } catch(Throwable failed) {done.completeExceptionally(failed);}
                        finally {app.stop();stage.close();}
                    });
                    laid.play();
                });
                resized.play();
            } catch(Throwable failed) {done.completeExceptionally(failed);app.stop();stage.close();}
        });
        done.get(90,TimeUnit.SECONDS);
    }

    /** {@code PortraitView.MIN_FLEX_HEIGHT}：立绘弹性收缩的下限，只用来打印对照。 */
    private static final class ImageFloor { static final double VALUE=96; }

    /**
     * 用户真正看得见的那件事。
     *
     * <p>不能只断言「内容 ≤ 视口」：{@code setFitToHeight(true)} 会把内容高度直接设成视口高度，
     * 那条断言就成了恒真式。滚动条是否可见才区分得出"装得下"和"被压到底"。
     */
    private static void assertNoVerticalScrollBar(ScrollPane scroll,String what) {
        Node bar=scroll.lookup(".scroll-bar:vertical");
        if(bar!=null) assertFalse(bar.isVisible(),what+" 出现了竖向滚动条");
    }

    /** 内容比视口高，就是面板里出现竖向滚动条的充要条件。 */
    private static void assertFitsVertically(Scene scene,ScrollPane scroll,String what) {
        double content=scroll.getContent().getBoundsInLocal().getHeight();
        double viewport=scroll.getViewportBounds().getHeight();
        System.out.printf("%s 内容 %.1f / 视口 %.1f（余量 %.1f，scene %.0fx%.0f）%n",
                what,content,viewport,viewport-content,scene.getWidth(),scene.getHeight());
        assertTrue(content<=viewport+1,
                what+" 内容高 "+content+" 超过视口 "+viewport
                        +"（scene "+scene.getWidth()+"x"+scene.getHeight()+"）会出现竖向滚动条");
    }

    private static Path shot(TankTroubleApp app,Stage stage,double clock,String name) throws Exception {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        var drawFrame=TankTroubleApp.class.getDeclaredMethod("drawFrame",double.class);
        drawFrame.setAccessible(true);
        drawFrame.invoke(app,clock);
        Path folder=Path.of(System.getProperty("tanktrouble.probeDir","target/probe"));
        Files.createDirectories(folder);
        Path file=folder.resolve(name+".png");
        ImageIO.write(SwingFXUtils.fromFXImage(stage.getScene().snapshot(null),null),"png",file.toFile());
        return file;
    }

    private static void click(Scene scene,String selector) {
        scene.getRoot().applyCss();scene.getRoot().layout();
        Node node=scene.lookup(selector);assertNotNull(node,selector);
        assertFalse(node.isDisabled(),selector);
        if(node instanceof ButtonBase button) {
            button.fire();
        } else {
            Point2D point=node.localToScene(node.getBoundsInLocal().getWidth()/2,
                    node.getBoundsInLocal().getHeight()/2);
            Event.fireEvent(node,new MouseEvent(MouseEvent.MOUSE_CLICKED,point.getX(),point.getY(),
                    point.getX(),point.getY(),MouseButton.PRIMARY,1,
                    false,false,false,false,true,false,false,true,false,false,
                    new PickResult(node,point.getX(),point.getY())));
        }
    }
}
