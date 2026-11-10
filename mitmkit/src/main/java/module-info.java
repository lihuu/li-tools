module io.github.lihuu.mitmkit {
    requires io.github.lihuu.clicommon;
    requires info.picocli;
    requires org.bouncycastle.provider;
    requires org.bouncycastle.pkix;
    requires org.bouncycastle.util;
    requires static org.jetbrains.annotations;

    opens io.github.lihuu.mitmkit.cli to info.picocli;

    exports io.github.lihuu.mitmkit.app;
    exports io.github.lihuu.mitmkit.cli;
}
