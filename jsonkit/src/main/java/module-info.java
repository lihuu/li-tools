module io.github.lihuu.jsonkit {
    requires io.github.lihuu.clicommon;
    requires info.picocli;
    requires com.fasterxml.jackson.core;
    requires io.github.treetrail.jsonpath;
    requires static org.jetbrains.annotations;

    opens io.github.lihuu.jsonkit.cli to info.picocli;

    exports io.github.lihuu.jsonkit.app;
    exports io.github.lihuu.jsonkit.cli;
}
