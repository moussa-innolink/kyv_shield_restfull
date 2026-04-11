package sn.innolink.kyvshield.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A single entity match from AML/sanctions screening.
 */
public final class AMLMatch {

    private final String entityId;
    private final String name;
    private final double score;
    private final List<String> datasets;
    private final List<String> topics;

    private AMLMatch(String entityId, String name, double score,
                     List<String> datasets, List<String> topics) {
        this.entityId = entityId;
        this.name     = name;
        this.score    = score;
        this.datasets = Collections.unmodifiableList(datasets);
        this.topics   = Collections.unmodifiableList(topics);
    }

    public String getEntityId() { return entityId; }
    public String getName()     { return name; }
    public double getScore()    { return score; }
    public List<String> getDatasets() { return datasets; }
    public List<String> getTopics()   { return topics; }

    public static AMLMatch fromJson(JSONObject json) {
        String entityId = json.optString("entity_id", "");
        String name     = json.optString("name", "");
        double score    = json.optDouble("score", 0.0);

        List<String> datasets = new ArrayList<>();
        JSONArray dsArr = json.optJSONArray("datasets");
        if (dsArr != null) {
            for (int i = 0; i < dsArr.length(); i++) {
                datasets.add(dsArr.optString(i, ""));
            }
        }

        List<String> topics = new ArrayList<>();
        JSONArray tpArr = json.optJSONArray("topics");
        if (tpArr != null) {
            for (int i = 0; i < tpArr.length(); i++) {
                topics.add(tpArr.optString(i, ""));
            }
        }

        return new AMLMatch(entityId, name, score, datasets, topics);
    }

    @Override
    public String toString() {
        return "AMLMatch{name='" + name + "', score=" + score
                + ", datasets=" + datasets + ", topics=" + topics + '}';
    }
}
