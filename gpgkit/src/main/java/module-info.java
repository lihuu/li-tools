module io.github.lihuu.gpgkit {
    requires io.github.lihuu.clicommon;
    requires info.picocli;
    requires static org.jetbrains.annotations;

    opens io.github.lihuu.gpgkit to info.picocli;
    exports io.github.lihuu.gpgkit;
}
