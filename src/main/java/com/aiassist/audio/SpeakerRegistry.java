package com.aiassist.audio;

import java.util.ArrayList;
import java.util.List;

/**
 * Assigns stable Speaker-1 / Speaker-2 / ... labels to meeting voices by
 * clustering the x-vectors the speaker model produces per utterance:
 * cosine similarity against running centroids, new centroid when nothing
 * is close enough. Lightweight online clustering — good enough to tell
 * participants apart, not a forensic voiceprint.
 */
final class SpeakerRegistry {

    private static final double SIMILARITY_THRESHOLD = 0.60;

    private final List<double[]> centroids = new ArrayList<>();
    private final List<Integer> counts = new ArrayList<>();

    synchronized String assign(double[] vector) {
        int best = -1;
        double bestSimilarity = -1;
        for (int i = 0; i < centroids.size(); i++) {
            double similarity = cosine(centroids.get(i), vector);
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                best = i;
            }
        }
        if (best >= 0 && bestSimilarity >= SIMILARITY_THRESHOLD) {
            // fold into the running centroid so the voice profile stabilizes
            double[] centroid = centroids.get(best);
            int count = counts.get(best);
            for (int d = 0; d < centroid.length; d++) {
                centroid[d] = (centroid[d] * count + vector[d]) / (count + 1);
            }
            counts.set(best, count + 1);
            return label(best);
        }
        centroids.add(vector.clone());
        counts.add(1);
        return label(centroids.size() - 1);
    }

    private static String label(int index) {
        return "Speaker-" + (index + 1);
    }

    private static double cosine(double[] a, double[] b) {
        int length = Math.min(a.length, b.length);
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return normA == 0 || normB == 0 ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
