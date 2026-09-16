package tanktrouble;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Set;
import java.util.List;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Small offline test entry point for development machines without Maven's console provider. */
public final class TestLauncher {
    private TestLauncher() {}

    public static void main(String[] args) throws ClassNotFoundException {
        Path classes=Path.of(args.length==0?"target/multi-test-check":args[0]).toAbsolutePath();
        List<? extends DiscoverySelector> selectors=args.length>1?
                List.of(DiscoverySelectors.selectClass(Class.forName(args[1]))):
                DiscoverySelectors.selectClasspathRoots(Set.of(classes));
        LauncherDiscoveryRequest request=LauncherDiscoveryRequestBuilder.request()
                .selectors(selectors)
                .build();
        SummaryGeneratingListener listener=new SummaryGeneratingListener();
        Launcher launcher=LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);
        var summary=listener.getSummary();
        summary.printTo(new PrintWriter(System.out,true));
        if(summary.getTotalFailureCount()>0) {
            summary.printFailuresTo(new PrintWriter(System.out,true));
            System.exit(1);
        }
    }
}
