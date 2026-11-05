module io.github.lihuu.dukit {
    requires io.github.lihuu.clicommon;
    requires info.picocli;
    requires org.jline.terminal;
    requires org.jline.terminal.ffm;
    requires static org.jetbrains.annotations;

    opens io.github.lihuu.dukit.cli to info.picocli;

    exports io.github.lihuu.dukit.app;
    exports io.github.lihuu.dukit.cli;
}
