package sn.innolink.kyvshield.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A photo extracted from a document image (e.g. the portrait photo on a CIN card).
 *
 * <p>The image is Base64-encoded JPEG data. Bounding box coordinates are expressed
 * in pixels relative to the submitted document image.
 */
public final class ExtractedPhoto {

    private final String image;
    private final double confidence;
    private final List<Double> bbox;
    private final double area;
    private final int width;
    private final int height;

    private ExtractedPhoto(
            String image,
            double confidence,
            List<Double> bbox,
            double area,
            int width,
            int height) {
        this.image      = image;
        this.confidence = confidence;
        this.bbox       = Collections.unmodifiableList(bbox);
        this.area       = area;
        this.width      = width;
        this.height     = height;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    /**
     * Returns the Base64-encoded JPEG image data of the extracted photo.
     */
    public String getImage() {
        return image;
    }

    /**
     * Returns the model confidence for this extraction in the range {@code [0, 1]}.
     */
    public double getConfidence() {
        return confidence;
    }

    /**
     * Returns the bounding box as a list {@code [x, y, width, height]} in pixels.
     * Never {@code null}; may be empty if not provided.
     */
    public List<Double> getBbox() {
        return bbox;
    }

    /** Returns the area of the bounding box in pixels². */
    public double getArea() {
        return area;
    }

    /** Returns the width of the extracted region in pixels. */
    public int getWidth() {
        return width;
    }

    /** Returns the height of the extracted region in pixels. */
    public int getHeight() {
        return height;
    }

    // ── Deserialisation ───────────────────────────────────────────────────────

    /**
     * Deserialises an {@link ExtractedPhoto} from a JSON object.
     *
     * @param json source JSON object
     * @return parsed instance
     */
    public static ExtractedPhoto fromJson(JSONObject json) {
        String image      = json.optString("image", "");
        double confidence = json.optDouble("confidence", 0.0);
        double area       = json.optDouble("area", 0.0);
        int    width      = json.optInt("width", 0);
        int    height     = json.optInt("height", 0);

        List<Double> bbox = new ArrayList<>();
        JSONArray bboxArr  = json.optJSONArray("bbox");
        if (bboxArr != null) {
            for (int i = 0; i < bboxArr.length(); i++) {
                bbox.add(bboxArr.optDouble(i));
            }
        }

        return new ExtractedPhoto(image, confidence, bbox, area, width, height);
    }

    @Override
    public String toString() {
        return "ExtractedPhoto{confidence=" + confidence
                + ", width=" + width + ", height=" + height + '}';
    }
}
