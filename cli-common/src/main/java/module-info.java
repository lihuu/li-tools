module io.github.lihuu.clicommon {
    requires transitive info.picocli;
    requires static org.jetbrains.annotations;

    exports io.github.lihuu.clicommon.cli;
    exports io.github.lihuu.clicommon.format;
    exports io.github.lihuu.clicommon.json;
    exports io.github.lihuu.clicommon.style;
    exports io.github.lihuu.clicommon.system;

    opens io.github.lihuu.clicommon.cli to info.picocli;
}
