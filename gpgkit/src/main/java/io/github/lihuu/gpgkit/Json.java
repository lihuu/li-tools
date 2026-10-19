package io.github.lihuu.gpgkit;

import io.github.lihuu.clicommon.json.JsonWriter;

/**
 * Lightweight JSON document builder for gpgkit, delegating to cli-common JsonWriter.
 */
final class Json {
    private final JsonWriter delegate = new JsonWriter();

    Json beginObject() {
        delegate.beginObject();
        return this;
    }

    Json endObject() {
        delegate.endObject();
        return this;
    }

    Json beginArray() {
        delegate.beginArray();
        return this;
    }

    Json endArray() {
        delegate.endArray();
        return this;
    }

    Json name(String name) {
        delegate.name(name);
        return this;
    }

    Json value(String value) {
        delegate.value(value);
        return this;
    }

    Json value(long value) {
        delegate.value(value);
        return this;
    }

    Json value(boolean value) {
        delegate.value(value);
        return this;
    }

    Json nullValue() {
        delegate.nullValue();
        return this;
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
