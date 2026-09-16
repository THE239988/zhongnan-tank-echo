package tanktrouble;

import tanktrouble.view.TankTroubleApp;
import tanktrouble.net.GameServer;

public final class App {
    private App() {}
    public static void main(String[] args) throws Exception {
        if(args.length>0&&args[0].equalsIgnoreCase("server")) {
            GameServer.main(java.util.Arrays.copyOfRange(args,1,args.length));return;
        }
        javafx.application.Application.launch(TankTroubleApp.class,args);
    }
}
