module io.github.lihuu.tokenkit {
    requires io.github.lihuu.clicommon;
    requires info.picocli;
    requires java.sql;
    requires org.xerial.sqlitejdbc;
    requires static org.jetbrains.annotations;

    opens io.github.lihuu.tokenkit.cli to info.picocli;

    exports io.github.lihuu.tokenkit.app;
    exports io.github.lihuu.tokenkit.cli;
    exports io.github.lihuu.tokenkit.core.crypto;
    exports io.github.lihuu.tokenkit.core.key;
    exports io.github.lihuu.tokenkit.core.model;
    exports io.github.lihuu.tokenkit.core.storage;
    exports io.github.lihuu.tokenkit.core.migration;
    exports io.github.lihuu.tokenkit.util;
}
