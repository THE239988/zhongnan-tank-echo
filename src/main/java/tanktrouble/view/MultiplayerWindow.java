package tanktrouble.view;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import tanktrouble.controller.GameController;
import tanktrouble.model.core.TankGameModel;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.data.Rules;
import tanktrouble.net.*;
import tanktrouble.net.Protocol.*;

/**
 * Multiplayer shell. LAN and remote-server entry are independent modules sharing the same wire
 * protocol and authoritative room implementation after a connection is made.
 */
final class MultiplayerWindow {
    private final Stage stage=new Stage();
    private final BorderPane root=new BorderPane();
    private final VBox panel=new VBox(12);
    private final TextField playerName=new TextField(System.getProperty("tanktrouble.player",
            "玩家-"+ProcessHandle.current().pid()));
    private final Label status=new Label("选择联机模块");

    private final TextField lanRoomName=new TextField("同网作战室");
    private final ChoiceBox<BattleType> lanBattleType=new ChoiceBox<>();
    private final TextField lanAddress=new TextField("127.0.0.1");
    private final TextField lanPort=new TextField("7777");
    private GameServer hostedServer;
    private LanDiscovery.Listener discovery;
    private List<LanDiscovery.Found> lanRooms=List.of();
    private long nextLanRefresh;
    private String renderedLanRooms="";

    private final TextField serverAddress=new TextField("");
    private final TextField serverPort=new TextField("7777");
    private final PasswordField serverPassword=new PasswordField();
    private final TextField serverRoomName=new TextField("回响作战室");
    private final ChoiceBox<BattleType> serverBattleType=new ChoiceBox<>();

    private MultiplayerModule module=MultiplayerModule.LAN;
    private GameClient client;
    private RemoteGameModel remote;
    private GameController controller;
    private AnimationTimer timer;
    private ChatPanel chatPanel;
    private int routedPlayer=-1;

    void show() {
        if(stage.isShowing()) {stage.toFront();return;}
        configureMode(lanBattleType);
        configureMode(serverBattleType);
        lanBattleType.setId("battle-type");
        serverBattleType.setId("server-battle-type");
        serverAddress.setId("server-address");
        serverPort.setId("server-port");
        serverPassword.setId("server-password");
        lanAddress.setId("lan-address");
        lanPort.setId("lan-port");
        serverPort.setMaxWidth(116);
        panel.setPadding(new Insets(38));
        panel.setMaxWidth(1020);
        panel.setMaxHeight(Region.USE_PREF_SIZE);
        panel.getStyleClass().addAll("menu-page","multiplayer-page");
        root.setId("multiplayer-root");
        resetShell();
        Scene scene=new Scene(root,1220,840);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/mecha.css")).toExternalForm());
        stage.setTitle("中南坦克 · 多人作战");
        stage.setScene(scene);
        stage.setMinWidth(1000);
        stage.setMinHeight(720);
        controller=new GameController(new TankGameModel());
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED,event->{
            if(chatPanel==null) return;
            if(event.getCode()==javafx.scene.input.KeyCode.ENTER&&!chatPanel.isComposing()) {
                chatPanel.focusComposer();event.consume();
            } else if(event.getCode()==javafx.scene.input.KeyCode.ESCAPE&&chatPanel.isComposing()) {
                chatPanel.releaseComposer();event.consume();
            }
        });
        controller.attach(scene,()->{},()->
                remote!=null&&remote.hasSnapshot()
                        &&(chatPanel==null||!chatPanel.isComposing()));
        stage.focusedProperty().addListener((o,was,focused)->{
            if(!focused&&remote!=null&&remote.hasSnapshot()) controller.loseFocus();
        });
        renderModuleMenu();
        stage.show();
        timer=new AnimationTimer(){public void handle(long now){pump(now);}};
        timer.start();
        stage.setOnCloseRequest(e->release());
        Platform.runLater(this::autoJoinIfRequested);
    }

    /**
     * Supports the local multi-window launcher and remote smoke runs without changing the normal
     * module menu. The property is only a shortcut: the selected module still owns the connection,
     * so the two connection paths remain independent.
     */
    private void autoJoinIfRequested() {
        String target=System.getProperty("tanktrouble.autoJoin");
        if(target==null||target.isBlank()) return;
        String host=target.trim();
        int port=Protocol.DEFAULT_PORT;
        int colon=host.lastIndexOf(':');
        if(colon>0) {
            String portText=host.substring(colon+1);
            host=host.substring(0,colon);
            try {
                port=Integer.parseInt(portText);
            } catch(NumberFormatException ignored) {
                port=Protocol.DEFAULT_PORT;
            }
        }
        module="server".equalsIgnoreCase(System.getProperty("tanktrouble.module","lan"))
                ?MultiplayerModule.SERVER:MultiplayerModule.LAN;
        selectModule(module);
        connect(host,port,System.getProperty("tanktrouble.password",""));
    }

    static InetAddress serverBindAddress() {return new InetSocketAddress(0).getAddress();}

    static String localLanAddress() {
        try {
            for(NetworkInterface network:Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if(!network.isUp()||network.isLoopback()||network.isVirtual()) continue;
                for(InetAddress candidate:Collections.list(network.getInetAddresses()))
                    if(!candidate.isLoopbackAddress()&&candidate.getHostAddress().indexOf(':')<0)
                        return candidate.getHostAddress();
            }
        } catch(Exception ignored) {
            // Manual discovery remains the fallback when adapter enumeration fails.
        }
        return "本机局域网 IP";
    }

    boolean isShowing() {return stage.isShowing();}

    private void configureMode(ChoiceBox<BattleType> box) {
        box.getItems().setAll(BattleType.values());
        box.setValue(BattleType.FREE_FOR_ALL);
        box.setConverter(new javafx.util.StringConverter<>() {
            public String toString(BattleType value){return value==null?"":value.label();}
            public BattleType fromString(String value){return BattleType.FREE_FOR_ALL;}
        });
    }

    private void resetShell() {
        root.setTop(null);
        StackPane shell=new StackPane(new MechaArtwork(false),panel);
        StackPane.setAlignment(panel,Pos.TOP_CENTER);
        StackPane.setMargin(panel,new Insets(28,0,28,0));
        root.setCenter(shell);
        root.getProperties().remove("multiplayer-renderer");
        root.getProperties().remove("multiplayer-arena");
        root.getProperties().remove("multiplayer-result");
    }

    private void renderModuleMenu() {
        stopDiscovery();
        closeHostedServer();
        panel.getChildren().clear();
        status.setText("选择局域网联机或服务器联机");
        status.getStyleClass().setAll("notice");
        VBox lan=modeSummary("LAN","局域网联机","一台电脑开房，同网玩家自动发现并加入。",
                "multiplayer-mode-ffa",()->selectModule(MultiplayerModule.LAN));
        VBox server=modeSummary("SERVER","服务器联机","连接公网服务器，查看房间列表并加入对局。",
                "multiplayer-mode-coop",()->selectModule(MultiplayerModule.SERVER));
        lan.setId("multiplayer-module-lan");
        server.setId("multiplayer-module-server");
        HBox modes=row(lan,server);
        HBox.setHgrow(lan,Priority.ALWAYS);HBox.setHgrow(server,Priority.ALWAYS);
        panel.getChildren().addAll(label("MULTIPLAYER / 独立联机模块","multiplayer-kicker"),
                label("选择联机方式","page-title"),
                label("两条连接路线互不干扰，进入房间后的规则和协议完全一致。","multiplayer-lead"),
                modes,status);
    }

    private VBox modeSummary(String code,String title,String copy,String style,Runnable action) {
        VBox box=new VBox(5,label(code,"multiplayer-mode-code"),label(title,"multiplayer-mode-title"),
                label(copy,"multiplayer-mode-copy"));
        box.getStyleClass().addAll("multiplayer-mode",style);
        box.setOnMouseClicked(event->action.run());
        box.setFocusTraversable(true);
        box.setOnKeyPressed(event->{
            if(event.getCode()==javafx.scene.input.KeyCode.ENTER||event.getCode()==javafx.scene.input.KeyCode.SPACE)
                action.run();
        });
        return box;
    }

    void selectModule(MultiplayerModule next) {
        module=next;
        status.setText(next==MultiplayerModule.LAN?"等待开房或发现同网房间":"输入服务器地址和口令");
        if(module==MultiplayerModule.LAN) startDiscovery();
        else stopDiscovery();
        renderConnectionSetup();
    }

    private void renderConnectionSetup() {
        resetShell();
        panel.getChildren().clear();
        status.getStyleClass().setAll("notice");
        if(module==MultiplayerModule.LAN) renderLanConnect();
        else renderServerConnect();
    }

    private void renderLanConnect() {
        Button host=button("创建本机房间",this::startLanHost);
        host.setId("multiplayer-host");
        host.getStyleClass().add("primary");
        Button connect=button("连接局域网服务器",()->connect(lanAddress.getText().trim(),
                parsePort(lanPort.getText(),Protocol.DEFAULT_PORT),""));
        connect.setId("lan-connect");
        Button back=button("返回联机模块",this::renderModuleMenu);
        back.setId("multiplayer-module-back");
        VBox ffa=modeSummary("FREE FOR ALL","多人混战","2–5 名玩家各自为营，最后存活者胜出。",
                "multiplayer-mode-ffa",()->{});
        VBox coop=modeSummary("CO-OP RAID","合作讨伐","全员同阵营，共同击破强化 Boss。",
                "multiplayer-mode-coop",()->{});
        HBox modes=row(ffa,coop);
        HBox.setHgrow(ffa,Priority.ALWAYS);
        HBox.setHgrow(coop,Priority.ALWAYS);
        VBox discovered=new VBox(7);discovered.setId("lan-room-list");
        populateLanRooms(discovered);
        panel.getChildren().addAll(
                label("LAN / AUTHORITATIVE SERVER","multiplayer-kicker"),
                label("局域网联机","page-title"),
                label("连接同一局域网，进入独立房间并控制自己的坦克。","multiplayer-lead"),
                modes,new Separator(),
                row(label("玩家代号","eyebrow"),playerName),
                row(label("服务器","eyebrow"),lanAddress,label("端口","eyebrow"),lanPort),
                row(label("首选玩法","eyebrow"),lanBattleType),
                row(host,connect,back),
                label("自动发现的同网房间","section-title"),discovered,status);
    }

    private void renderServerConnect() {
        Button connect=button("连接服务器",()->connect(serverAddress.getText().trim(),
                parsePort(serverPort.getText(),Protocol.DEFAULT_PORT),serverPassword.getText()));
        connect.setId("multiplayer-connect");
        connect.getStyleClass().add("primary");
        Button back=button("返回联机模块",this::renderModuleMenu);
        back.setId("multiplayer-module-back");
        panel.getChildren().addAll(
                label("REMOTE / AUTHORITATIVE SERVER","multiplayer-kicker"),
                label("服务器联机","page-title"),
                label("连接公网服务器并进入独立房间，客户端和服务端必须同为 3.0。","multiplayer-lead"),
                row(label("玩家代号","eyebrow"),playerName),
                row(label("服务器","eyebrow"),serverAddress,label("端口","eyebrow"),serverPort),
                row(label("口令","eyebrow"),serverPassword,connect),
                back,status);
    }

    private static int parsePort(String text,int fallback) {
        try {return Integer.parseInt(text.trim());}
        catch(NumberFormatException bad) {return fallback;}
    }

    private void startLanHost() {
        if(hostedServer!=null) return;
        try {
            hostedServer=new GameServer();
            int actual=hostedServer.start(serverBindAddress(),0);
            hostedServer.startDiscovery();
            lanAddress.setText("127.0.0.1");
            lanPort.setText(Integer.toString(actual));
            connect("127.0.0.1",actual,"");
        } catch(Exception error) {
            status.setText("服务器启动失败："+error.getMessage());
        }
    }

    private void connect(String host,int port,String password) {
        if(host==null||host.isBlank()) {status.setText("服务器地址不能为空");return;}
        status.setText("正在连接…");
        String name=playerName.getText().trim();
        Thread worker=new Thread(()->{
            try {
                GameClient next=new GameClient();
                next.connect(host,port,name,password==null?"":password,3500);
                Platform.runLater(()->{
                    if(client!=null) client.close();
                    client=next;
                    remote=new RemoteGameModel(next,0,name);
                    routedPlayer=-1;
                    controller.useModel(remote,0);
                    renderBrowser();
                });
            } catch(Exception error) {
                Platform.runLater(()->status.setText("连接失败："+error.getMessage()));
            }
        },"multiplayer-connect");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderBrowser() {
        resetShell();
        panel.getChildren().clear();
        if(module==MultiplayerModule.LAN) renderLanBrowser();
        else renderServerBrowser();
    }

    private void renderLanBrowser() {
        if(hostedServer!=null)
            status.setText("本机服务器  "+localLanAddress()+":"+hostedServer.port()
                    +"  ·  将此地址告诉同一局域网内的玩家");
        Button create=button("创建房间",()->remote.createRoom(lanRoomName.getText(),
                TankGameModel.MAX_PLAYERS,lanBattleType.getValue()));
        create.setId("create-room");
        create.getStyleClass().add("primary");
        VBox discovered=new VBox(7);discovered.setId("lan-room-list");
        populateLanRooms(discovered);
        VBox rooms=new VBox(7);rooms.setId("network-room-list");
        populateServerRooms(rooms);
        panel.getChildren().addAll(
                label("ROOM DIRECTORY / 作战大厅","multiplayer-kicker"),
                label("选择作战房间","page-title"),
                row(label("房间名","eyebrow"),lanRoomName,label("玩法","eyebrow"),lanBattleType),
                row(create,button("断开连接",this::disconnect)),
                label("同一网络发现的房间","section-title"),discovered,
                label("服务器房间列表","section-title"),rooms,status);
    }

    private void renderServerBrowser() {
        Button create=button("创建房间",()->remote.createRoom(serverRoomName.getText(),
                TankGameModel.MAX_PLAYERS,serverBattleType.getValue()));
        create.setId("create-room");
        create.getStyleClass().add("primary");
        VBox rooms=new VBox(7);rooms.setId("network-room-list");
        populateServerRooms(rooms);
        panel.getChildren().addAll(
                label("REMOTE ROOM BROWSER / 服务器大厅","multiplayer-kicker"),
                label("选择或创建房间","page-title"),
                row(label("房间名","eyebrow"),serverRoomName,label("玩法","eyebrow"),serverBattleType),
                row(create,button("断开连接",this::disconnect)),
                label("服务器房间","section-title"),rooms,status);
    }

    private void populateServerRooms(VBox rooms) {
        if(remote==null) return;
        if(remote.roomList().isEmpty()) {
            rooms.getChildren().add(label("服务器上还没有房间，创建一个吧。","muted"));
            return;
        }
        for(RoomInfo info:remote.roomList()) {
            Button join=button(info.label(),()->remote.joinRoom(info.id()));
            join.setMaxWidth(Double.MAX_VALUE);
            join.setDisable(!info.joinable());
            rooms.getChildren().add(join);
        }
    }

    private void populateLanRooms(VBox discovered) {
        discovered.getChildren().clear();
        if(lanRooms.isEmpty()) {
            discovered.getChildren().add(label("正在搜索同网房间…","muted"));
            return;
        }
        for(LanDiscovery.Found room:lanRooms) {
            Button join=button(room.label(),()->connect(room.host(),room.port(),""));
            join.setMaxWidth(Double.MAX_VALUE);
            join.setDisable(!room.joinable());
            discovered.getChildren().add(join);
        }
    }

    private void renderRoom() {
        resetShell();
        panel.getChildren().clear();
        LobbyState lobby=remote.lobby();
        Label title=label(remote.snapshot().mode()==Mode.COOP?"合作讨伐准备":"多人混战准备","page-title");
        VBox roster=new VBox(6);roster.setId("multiplayer-roster");
        if(lobby!=null) for(LobbyState.Member member:lobby.members()) roster.getChildren().add(rosterRow(lobby,member));
        ChoiceBox<TankType> tank=new ChoiceBox<>();
        tank.getItems().setAll(TankType.values());
        tank.setValue(remote.chosenTank(remote.localPlayer()));
        tank.setId("multiplayer-tank-select");
        tank.setConverter(new javafx.util.StringConverter<>() {
            public String toString(TankType value){return value==null?"":Labels.tank(value);}
            public TankType fromString(String value){return TankType.BALANCED;}
        });
        tank.setOnAction(e->remote.selectTank(remote.localPlayer(),tank.getValue()));
        Button ready=button("准备 / 取消准备",()->remote.setReady(!localReady()));
        Button start=button("房主开始对局",remote::requestStart);
        start.getStyleClass().add("primary");
        if(lobby!=null) start.setDisable(!remote.isHost()||lobby.members().size()<2
                ||lobby.members().stream().anyMatch(member->!member.ready()));
        chatPanel=new ChatPanel(remote::say,380);
        panel.getChildren().addAll(
                label(module.label()+" / 房间准备","multiplayer-kicker"),title,
                label(remote.snapshot().mode()==Mode.COOP?"全员同阵营，共同击破强化 Boss":"各自为营，最后存活的坦克获胜",
                        "multiplayer-lead"),
                new Separator(),roster,row(tank,ready,start),
                button("返回大厅",remote::leaveRoom),status,chatPanel);
    }

    private HBox rosterRow(LobbyState lobby,LobbyState.Member member) {
        Label line=label((member.player()==lobby.hostPlayer()?"房主  ":"")+"P"+(member.player()+1)
                +"  "+member.name()+"  ·  "+Labels.tank(member.tank())
                +(member.ready()?"  已准备":"  未准备"),"lobby-member");
        HBox row=new HBox(8,line);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(line,Priority.ALWAYS);
        line.setMaxWidth(Double.MAX_VALUE);
        if(member.player()==remote.localPlayer()) row.getStyleClass().add("lobby-you");
        if(remote.isHost()&&member.player()!=remote.localPlayer()) {
            Button kick=button("移出",()->confirmKick(member));
            kick.getStyleClass().add("lobby-kick");
            row.getChildren().add(kick);
        }
        return row;
    }

    private void confirmKick(LobbyState.Member member) {
        Alert confirmation=new Alert(Alert.AlertType.CONFIRMATION,
                "确定将 "+member.name()+" 移出房间吗？",ButtonType.CANCEL,ButtonType.OK);
        confirmation.setHeaderText("移出玩家");
        confirmation.showAndWait().filter(ButtonType.OK::equals)
                .ifPresent(choice->remote.kick(member.player()));
    }

    private boolean localReady() {
        LobbyState lobby=remote.lobby();
        return lobby!=null&&lobby.members().stream()
                .anyMatch(member->member.player()==remote.localPlayer()&&member.ready());
    }

    private void renderBattle() {
        Canvas canvas=new Canvas(Rules.WIDTH,Rules.HEIGHT);
        canvas.setId("multiplayer-canvas");
        BattleRenderer renderer=new BattleRenderer(canvas);
        renderer.setLocalPlayer(remote.localPlayer());
        StackPane arena=new StackPane(canvas);
        arena.setStyle("-fx-background-color:#111d24;");
        Label mode=label(remote.snapshot().mode()==Mode.COOP?"合作讨伐 / TEAM":"多人混战 / FREE FOR ALL","edition");
        Button leave=button("退出房间",()->{remote.leaveRoom();renderBrowser();});
        HBox header=row(mode,new Region(),leave);
        HBox.setHgrow(header.getChildren().get(1),Priority.ALWAYS);
        if(chatPanel==null) chatPanel=new ChatPanel(remote::say,340);
        chatPanel.setVisible(true);chatPanel.setManaged(true);
        BorderPane battleBody=new BorderPane();
        battleBody.setCenter(arena);
        battleBody.setRight(chatPanel);
        BorderPane.setMargin(chatPanel,new Insets(12));
        root.setTop(header);
        root.setCenter(battleBody);
        arena.widthProperty().addListener((o,a,b)->fit(canvas,arena));
        arena.heightProperty().addListener((o,a,b)->fit(canvas,arena));
        root.getProperties().put("multiplayer-renderer",renderer);
        root.getProperties().put("multiplayer-arena",arena);
        fit(canvas,arena);
    }

    private void fit(Canvas canvas,StackPane arena) {
        double scale=Math.max(.1,Math.min(arena.getWidth()/Rules.WIDTH,arena.getHeight()/Rules.HEIGHT));
        canvas.setWidth(Rules.WIDTH*scale);
        canvas.setHeight(Rules.HEIGHT*scale);
    }

    private void pump(long now) {
        if(remote==null) {
            refreshLanRooms(now);
            return;
        }
        boolean changed=remote.pump();
        refreshLanRooms(now);
        if(chatPanel!=null) chatPanel.apply(remote.chatLog());
        if(remote.localPlayer()!=routedPlayer) {
            routedPlayer=remote.localPlayer();
            controller.useModel(remote,routedPlayer);
        }
        Notice notice=remote.takeNotice();
        if(notice!=null) status.setText(notice.text());
        if(!remote.isConnected()) {
            String failure=remote.failure()==null?"连接已关闭":remote.failure();
            disconnect();
            status.setText(failure);
            return;
        }
        if(remote.hasSnapshot()) {
            if(root.getProperties().get("multiplayer-renderer")==null) renderBattle();
            BattleRenderer renderer=(BattleRenderer)root.getProperties().get("multiplayer-renderer");
            double clock=now/1_000_000_000.;
            renderer.draw(remote.snapshot(),clock);
            for(GameEvent event:remote.drainEvents()) {
                if(event.type()==GameEventType.BOUNCE)
                    renderer.addBounce(event.player(),event.x(),event.y());
                else if(!((event.type()==GameEventType.HIT_ENEMY
                        ||event.type()==GameEventType.HIT_PLAYER)
                        &&Double.isNaN(event.incomingAngle())))
                    renderer.addEffect(event.type(),event.player(),event.x(),event.y(),
                            event.sourceSeat(),event.incomingAngle());
            }
            if(remote.snapshot().state()==State.RESULT) showBattleResult();
            else clearBattleResult();
        } else if(changed) {
            if(remote.inLobby()) renderBrowser();
            else renderRoom();
        }
    }

    private void refreshLanRooms(long now) {
        if(module!=MultiplayerModule.LAN||discovery==null||now<nextLanRefresh) return;
        nextLanRefresh=now+500_000_000L;
        List<LanDiscovery.Found> found=discovery.rooms();
        String key=found.stream()
                .map(room->room.roomId()+"@"+room.host()+":"+room.port()+"@"+room.name()
                        +"@"+room.players()+"/"+room.capacity()+"@"+room.phase())
                .collect(java.util.stream.Collectors.joining("|"));
        if(key.equals(renderedLanRooms)) return;
        renderedLanRooms=key;
        lanRooms=found;
        VBox discovered=(VBox)panel.lookup("#lan-room-list");
        if(discovered!=null) populateLanRooms(discovered);
    }

    private void startDiscovery() {
        if(discovery!=null) return;
        discovery=new LanDiscovery.Listener();
        discovery.start();
    }

    private void stopDiscovery() {
        if(discovery==null) return;
        discovery.close();
        discovery=null;
        lanRooms=List.of();
        renderedLanRooms="";
    }

    private void closeHostedServer() {
        if(hostedServer==null) return;
        hostedServer.close();
        hostedServer=null;
    }

    private void showBattleResult() {
        if(root.getProperties().containsKey("multiplayer-result")) return;
        StackPane arena=(StackPane)root.getProperties().get("multiplayer-arena");
        if(arena==null) return;
        Snapshot snapshot=remote.snapshot();
        boolean won=snapshot.mode()==Mode.COOP?snapshot.result()==Result.VICTORY:
                snapshot.tanks().stream().anyMatch(tank->tank.player()==remote.localPlayer()&&tank.hp()>0);
        Label title=label(won?"作战胜利":"作战结束","modal-title");
        Label detail=label(snapshot.mode()==Mode.COOP
                ?(won?"Boss 已被联合火力击破":"全队已失去作战能力")
                :(won?"你是最后存活的坦克":"本轮胜者已经产生"),"result-stats");
        boolean host=remote.isHost();
        boolean canRematch=host&&remote.lobby()!=null&&remote.lobby().members().size()>=2;
        Button rematch=button(host?"再来一局":"等待房主重新开始",remote::requestStart);
        rematch.setDisable(!canRematch);
        rematch.getStyleClass().add("primary");
        Button leave=button("离开房间",()->{remote.leaveRoom();renderBrowser();});
        VBox result=new VBox(18,title,detail,rematch,leave);
        result.setId("multiplayer-result");
        result.setAlignment(Pos.CENTER);
        result.setMaxSize(420,220);
        result.getStyleClass().add("modal");
        StackPane scrim=new StackPane(result);
        scrim.getStyleClass().add("scrim");
        arena.getChildren().add(scrim);
        root.getProperties().put("multiplayer-result",scrim);
    }

    /** Removes the stale result overlay when the host starts the next match. */
    private void clearBattleResult() {
        Object previous=root.getProperties().remove("multiplayer-result");
        if(previous instanceof javafx.scene.Node node&&node.getParent() instanceof Pane parent)
            parent.getChildren().remove(node);
    }

    private void disconnect() {
        if(client!=null) client.close();
        client=null;
        remote=null;
        routedPlayer=-1;
        closeHostedServer();
        if(module==MultiplayerModule.LAN) startDiscovery();
        renderConnectionSetup();
    }

    private void release() {
        if(timer!=null) {timer.stop();timer=null;}
        stopDiscovery();
        if(client!=null) client.close();
        closeHostedServer();
        client=null;
        remote=null;
    }

    void close() {
        release();
        stage.hide();
    }

    private static Label label(String text,String style) {
        Label label=new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }

    private static Button button(String text,Runnable action) {
        Button button=new Button(text);
        button.setOnAction(event->action.run());
        return button;
    }

    private static HBox row(javafx.scene.Node... nodes) {
        HBox box=new HBox(10,nodes);
        box.setAlignment(Pos.CENTER_LEFT);
        for(var node:nodes) if(node instanceof TextField) HBox.setHgrow(node,Priority.ALWAYS);
        return box;
    }
}
