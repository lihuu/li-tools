module io.github.lihuu.certkit {
    requires io.github.lihuu.clicommon;
    requires info.picocli;
    requires static org.jetbrains.annotations;

    opens io.github.lihuu.certkit to info.picocli;
    exports io.github.lihuu.certkit;
}
