package tanktrouble.view;

/** The two independent multiplayer entry paths. */
enum MultiplayerModule {
    LAN("局域网联机"),
    SERVER("服务器联机");

    private final String label;

    MultiplayerModule(String label) {
        this.label=label;
    }

    String label() {
        return label;
    }
}
