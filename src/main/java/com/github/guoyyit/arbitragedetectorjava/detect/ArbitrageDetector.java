package com.github.guoyyit.arbitragedetectorjava.detect;

import com.github.guoyyit.arbitragedetectorjava.config.DetectorProperties;
import com.github.guoyyit.arbitragedetectorjava.domain.Hop;
import com.github.guoyyit.arbitragedetectorjava.domain.Opportunity;
import com.github.guoyyit.arbitragedetectorjava.domain.Quote;
import com.github.guoyyit.arbitragedetectorjava.quote.QuoteSnapshot;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds profitable currency cycles within a single immutable {@link QuoteSnapshot}.
 *
 * Every quote yields two directed edges between currencies:
 * <ul>
 *   <li>SELL base -> quote at the bid (1 base = bid quote),</li>
 *   <li>BUY quote -> base at the ask (1 base costs ask quote).</li>
 * </ul>
 * Per-hop fee and slippage are both modeled against the taker: an edge with
 * raw rate {@code r} executes at {@code r * (1-feeRate) * (1-slippageRate)}.
 * A cycle returning rate strictly greater than 1 is profitable; its net rate
 * ({@code product-1}) must exceed the configured minimum profit and its
 * executable size (in start-currency units, propagated through every hop) must
 * meet the minimum volume.
 *
 * Determinism: the graph is built from the snapshot in sorted key order, DFS
 * visits edges in sorted order, cycles are only accepted when the start node
 * is the lexicographically smallest currency in the cycle (which removes
 * rotations), and the result is sorted by (netRate desc, id asc). Same
 * snapshot + same config therefore always yields the exact same list.
 */
@Component
public class ArbitrageDetector {

    public List<Opportunity> detect(QuoteSnapshot snapshot, DetectorProperties config) {
        int maxHops = Math.max(2, config.getMaxCycleLength());
        double costFactor = (1.0 - config.getFeeRate()) * (1.0 - config.getSlippageRate());
        double minProfitRate = config.getMinProfitRate();
        double minVolume = config.getMinVolume();
        Instant now = snapshot.createdAt();

        // Build adjacency deterministically. Multiple quotes can provide the same
        // currency pair (different markets) - all edges are kept.
        Map<String, List<Edge>> adjacency = new LinkedHashMap<>();
        for (Quote q : snapshot.quotes()) {
            adjacency.computeIfAbsent(q.base(), k -> new ArrayList<>());
            adjacency.computeIfAbsent(q.quote(), k -> new ArrayList<>());
            // SELL base at bid: base -> quote; delivers bidQty*bid quote units
            addEdge(adjacency, new Edge(q.market(), q.base(), q.quote(), Hop.Side.SELL,
                    q.bid(), q.bidQty() * q.bid(), q.bidQty(), false));
            // BUY base at ask: quote -> base (1 quote buys 1/ask base);
            // delivers askQty base units
            addEdge(adjacency, new Edge(q.market(), q.quote(), q.base(), Hop.Side.BUY,
                    1.0 / q.ask(), q.askQty(), q.askQty(), true));
        }
        for (List<Edge> edges : adjacency.values()) {
            edges.sort(Comparator.comparing(Edge::sortKey));
        }

        List<Opportunity> result = new ArrayList<>();
        List<String> nodes = adjacency.keySet().stream().sorted().toList();
        for (String start : nodes) {
            dfs(start, start, new ArrayList<>(List.of(start)), new ArrayList<>(),
                    1.0, Double.POSITIVE_INFINITY, maxHops, adjacency, costFactor,
                    minProfitRate, minVolume, snapshot, now, result);
        }
        result.sort(Comparator.comparingDouble(Opportunity::netRate).reversed()
                .thenComparing(Opportunity::id));
        return result;
    }

    private void addEdge(Map<String, List<Edge>> adjacency, Edge edge) {
        if (!Double.isFinite(edge.rate) || edge.rate <= 0 || edge.size <= 0) {
            return;
        }
        adjacency.get(edge.from).add(edge);
    }

    private void dfs(String start, String current, List<String> path, List<Edge> edges,
                     double gross, double volumeStartUnits, int maxHops,
                     Map<String, List<Edge>> adjacency, double costFactor,
                     double minProfitRate, double minVolume, QuoteSnapshot snapshot,
                     Instant now, List<Opportunity> out) {
        if (!edges.isEmpty()) {
            Edge last = edges.get(edges.size() - 1);
            // size on the edge is quoted in its destination currency; convert to
            // start-currency units by walking the executed chain backwards.
            double sizeInStartUnits = last.size;
            for (int i = edges.size() - 1; i >= 0; i--) {
                Edge e = edges.get(i);
                // edge delivers 'size' units of e.to at executed rate e.rate*cost;
                // to produce X units of e.to you need X/(e.rate) units of e.from
                // (cost factor cancels for the quantity conversion).
                sizeInStartUnits = sizeInStartUnits / e.rate;
            }
            volumeStartUnits = Math.min(volumeStartUnits, sizeInStartUnits);

            if (current.equals(start)) {
                double netRate = gross * Math.pow(costFactor, edges.size()) - 1.0;
                // Only accept a cycle when its start is the smallest currency in
                // it: this makes each unique cycle emitted exactly once, free of
                // rotated duplicates.
                boolean startIsSmallest = true;
                for (String node : path) {
                    if (node.compareTo(start) < 0) {
                        startIsSmallest = false;
                        break;
                    }
                }
                if (startIsSmallest && netRate > minProfitRate
                        && volumeStartUnits >= minVolume) {
                    out.add(toOpportunity(edges, gross, netRate, volumeStartUnits,
                            snapshot.version(), now));
                }
                return; // a simple cycle: do not keep looping through start
            }
        }
        if (edges.size() >= maxHops) {
            return;
        }
        for (Edge e : adjacency.getOrDefault(current, List.of())) {
            // simple cycles only: a currency may appear once, except closing on start
            if (e.to.equals(start) || !path.contains(e.to)) {
                path.add(e.to);
                edges.add(e);
                dfs(start, e.to, path, edges, gross * e.rate, volumeStartUnits,
                        maxHops, adjacency, costFactor, minProfitRate, minVolume,
                        snapshot, now, out);
                edges.remove(edges.size() - 1);
                path.remove(path.size() - 1);
            }
        }
    }

    private Opportunity toOpportunity(List<Edge> edges, double gross, double netRate,
                                      double maxVolume, long snapshotVersion, Instant now) {
        List<Hop> hops = new ArrayList<>();
        StringBuilder id = new StringBuilder();
        for (Edge e : edges) {
            double execPrice = e.inverted ? 1.0 / e.rate : e.rate;
            hops.add(new Hop(e.market, e.from, e.to, e.side, execPrice, e.sizeBase));
            if (!id.isEmpty()) {
                id.append("|");
            }
            id.append(e.market).append(':').append(e.from).append('>')
                    .append(e.to).append(':').append(e.side);
        }
        return new Opportunity(id.toString(), hops, gross - 1.0, netRate, maxVolume,
                now, snapshotVersion);
    }

    /** Internal directed edge. {@code size} is depth in destination-currency units. */
    private static final class Edge {
        final String market;
        final String from;
        final String to;
        final Hop.Side side;
        final double rate;
        final double size;
        /** executable depth in the pair's base-currency units */
        final double sizeBase;
        final boolean inverted;

        Edge(String market, String from, String to, Hop.Side side,
             double rate, double size, double sizeBase, boolean inverted) {
            this.market = market;
            this.from = from;
            this.to = to;
            this.side = side;
            this.rate = rate;
            this.size = size;
            this.sizeBase = sizeBase;
            this.inverted = inverted;
        }

        String sortKey() {
            return to + "|" + market + "|" + side;
        }
    }
}
