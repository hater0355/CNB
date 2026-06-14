package chatapp;

import java.text.Normalizer;

final class Texts {
    private Texts() {
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String n = Normalizer.normalize(value, Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'D').toLowerCase();
    }

    static String safe(String value) {
        return value == null ? "" : value;
    }

    static String shortText(String value, int max) {
        if (value == null) {
            return "";
        }
        String compact = value.replace('\n', ' ').trim();
        return compact.length() <= max ? compact : compact.substring(0, max - 3) + "...";
    }
}
