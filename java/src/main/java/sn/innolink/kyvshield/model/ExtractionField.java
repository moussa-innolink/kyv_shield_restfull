package sn.innolink.kyvshield.model;

import org.json.JSONObject;

/**
 * A single extracted text field from a document image.
 *
 * <p>Each field carries both its machine-readable key and a human-readable label
 * suitable for display in a UI, along with the extracted value and optional
 * display metadata.
 */
public final class ExtractionField {

    private final String key;
    private final String documentKey;
    private final String label;
    private final String value;
    private final int displayPriority;
    private final String icon;

    private ExtractionField(
            String key,
            String documentKey,
            String label,
            String value,
            int displayPriority,
            String icon) {
        this.key             = key;
        this.documentKey     = documentKey;
        this.label           = label;
        this.value           = value;
        this.displayPriority = displayPriority;
        this.icon            = icon;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    /** Returns the machine-readable field key (e.g. {@code "numero_carte"}). */
    public String getKey() {
        return key;
    }

    /** Returns the key as defined in the document specification. */
    public String getDocumentKey() {
        return documentKey;
    }

    /** Returns the human-readable label for this field. */
    public String getLabel() {
        return label;
    }

    /** Returns the extracted value for this field. */
    public String getValue() {
        return value;
    }

    /**
     * Returns an ordering hint for display purposes — lower values indicate
     * higher display priority.
     */
    public int getDisplayPriority() {
        return displayPriority;
    }

    /**
     * Returns an optional icon identifier (e.g. a Material icon name), or
     * {@code null} if not set.
     */
    public String getIcon() {
        return icon;
    }

    // ── Deserialisation ───────────────────────────────────────────────────────

    /**
     * Deserialises an {@link ExtractionField} from a JSON object.
     *
     * @param json source JSON object
     * @return parsed instance
     */
    public static ExtractionField fromJson(JSONObject json) {
        String key             = json.optString("key", "");
        String documentKey     = json.optString("document_key", "");
        String label           = json.optString("label", "");
        String value           = json.optString("value", "");
        int    displayPriority = json.optInt("display_priority", 0);
        String icon            = json.isNull("icon") ? null : json.optString("icon", null);
        return new ExtractionField(key, documentKey, label, value, displayPriority, icon);
    }

    @Override
    public String toString() {
        return "ExtractionField{key='" + key + "', label='" + label
                + "', value='" + value + "'}";
    }
}
