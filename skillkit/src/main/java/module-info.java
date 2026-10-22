module io.github.lihuu.skillkit {
    requires io.github.lihuu.clicommon;
    requires info.picocli;
    requires static org.jetbrains.annotations;

    opens io.github.lihuu.skillkit.cli to info.picocli;

    exports io.github.lihuu.skillkit.app;
    exports io.github.lihuu.skillkit.cli;
}
