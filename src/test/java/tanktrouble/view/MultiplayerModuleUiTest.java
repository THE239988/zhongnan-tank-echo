package tanktrouble.view;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MultiplayerModuleUiTest {
    @Test void lanAndServerModulesOpenIndependently() throws Exception {
        assumeTrue(Boolean.getBoolean("fxSmoke"));
        try {
            Platform.startup(()->{});
        } catch(IllegalStateException alreadyStarted) {
            // The toolkit is shared by the other GUI smoke tests.
        }
        CountDownLatch finished=new CountDownLatch(1);
        Platform.runLater(()->{
            MultiplayerWindow window=new MultiplayerWindow();
            try {
                window.show();
                assertTrue(window.isShowing());
                window.selectModule(MultiplayerModule.SERVER);
                window.selectModule(MultiplayerModule.LAN);
            } finally {
                window.close();
                finished.countDown();
            }
        });
        assertTrue(finished.await(10,TimeUnit.SECONDS),"JavaFX module window did not close");
    }
}
