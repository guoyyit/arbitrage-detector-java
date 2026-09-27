package com.github.guoyyit.arbitragedetectorjava.detect;

import com.github.guoyyit.arbitragedetectorjava.config.ArbitrageProperties;
import com.github.guoyyit.arbitragedetectorjava.model.ArbitrageOpportunity;
import com.github.guoyyit.arbitragedetectorjava.model.Hop;
import com.github.guoyyit.arbitragedetectorjava.model.Quote;
import com.github.guoyyit.arbitragedetectorjava.state.QuoteSnapshot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pure, deterministic cycle detector. Same snapshot + same config
 * always yields the same opportunities in the same order.
 *
 * <p>Each quote contributes two directed edges:
 * <ul>
 *   <li>base -&gt; quote at {@code bid} (sell base), capacity {@code bidVolume} base units;</li>
 *   <li>quote -&gt; base at {@code 1/ask} (buy base), capacity {@code askVolume * ask} quote units.</li>
 * </ul>
 * A cycle of length 2..maxCycleLength is an opportunity when the product of
 * per-hop net rates (after fee and slippage) exceeds {@code 1 + minProfit}
 * and the bottleneck start volume reaches {@code minVolume}.
 *
 * <p>Determinism: nodes and edges are processed in sorted order; every
 * simple cycle is enumerated exactly once by requiring the cycle start to
 * be its lexicographically smallest currency.
 */
public class ArbitrageDetector {

    private record Edge(String market, String from, String to, double rate, double capacityFrom) {
        Hop toHop() {
            return new Hop(market, from, to, rate, capacityFrom);
        }
    }

    public List<ArbitrageOpportunity> detect(QuoteSnapshot snapshot, ArbitrageProperties config) {
        Map<String, List<Edge>> adj = buildGraph(snapshot);
        double costFactor = (1.0 - config.feeRate()) * (1.0 - config.slippageRate());
        List<ArbitrageOpportunity> found = new ArrayList<>();
        for (String start : adj.keySet()) {
            enumerate(start, start, adj, config, costFactor,
                    new HashSet<>(Set.of(start)), new ArrayDeque<>(),
                    1.0, 1.0, found, snapshot);
        }
        found.sort(Comparator.comparingDouble(ArbitrageOpportunity::netReturn).reversed()
                .thenComparing(ArbitrageOpportunity::signature));
        return List.copyOf(found);
    }

    private Map<String, List<Edge>> buildGraph(QuoteSnapshot snapshot) {
        Map<String, List<Edge>> adj = new TreeMap<>();
        for (Quote q : snapshot.all()) {
            addEdge(adj, new Edge(q.market(), q.base(), q.quote(), q.bid(), q.bidVolume()));
            addEdge(adj, new Edge(q.market(), q.quote(), q.base(),
                    1.0 / q.ask(), q.askVolume() * q.ask()));
        }
        for (List<Edge> edges : adj.values()) {
            edges.sort(Comparator.comparing(Edge::to).thenComparing(Edge::market));
        }
        return adj;
    }

    private void addEdge(Map<String, List<Edge>> adj, Edge e) {
        if (e.capacityFrom() > 0) {
            adj.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
        }
    }

    private void enumerate(String start, String current,
                           Map<String, List<Edge>> adj,
                           ArbitrageProperties config, double costFactor,
                           Set<String> visited, Deque<Edge> path,
                           double grossProduct, double netProduct,
                           List<ArbitrageOpportunity> out, QuoteSnapshot snapshot) {
        if (path.size() >= config.maxCycleLength()) {
            return;
        }
        for (Edge e : adj.getOrDefault(current, List.of())) {
            // The start node is the cycle minimum; never pass a smaller node.
            if (e.to().compareTo(start) < 0) {
                continue;
            }
            double gross = grossProduct * e.rate();
            double net = netProduct * e.rate() * costFactor;
            if (e.to().equals(start)) {
                if (path.size() + 1 >= 2) {
                    consider(start, path, e, gross, net, config, out, snapshot);
                }
                continue;
            }
            if (visited.contains(e.to())) {
                continue;
            }
            visited.add(e.to());
            path.addLast(e);
            enumerate(start, e.to(), adj, config, costFactor, visited, path,
                    gross, net, out, snapshot);
            path.removeLast();
            visited.remove(e.to());
        }
    }

    private void consider(String start, Deque<Edge> path, Edge closing,
                          double grossProduct, double netProduct,
                          ArbitrageProperties config, List<ArbitrageOpportunity> out,
                          QuoteSnapshot snapshot) {
        double netReturn = netProduct - 1.0;
        if (netReturn <= config.minProfit()) {
            return;
        }
        List<Edge> cycle = new ArrayList<>(path);
        cycle.add(closing);
        double maxVolume = bottleneckVolume(cycle);
        if (maxVolume < config.minVolume()) {
            return;
        }
        List<Hop> hops = new ArrayList<>(cycle.size());
        StringBuilder sig = new StringBuilder();
        for (Edge e : cycle) {
            hops.add(e.toHop());
            if (sig.length() > 0) {
                sig.append('|');
            }
            sig.append(e.market()).append(':').append(e.from()).append("->").append(e.to());
        }
        out.add(new ArbitrageOpportunity(sig.toString(), List.copyOf(hops),
                grossProduct - 1.0, netReturn, maxVolume,
                snapshot.version(), snapshot.createdAt()));
    }

    /** Bottleneck start amount: each hop capacity converted to start currency. */
    private double bottleneckVolume(List<Edge> cycle) {
        double min = Double.MAX_VALUE;
        double cumulativeRate = 1.0;
        for (Edge e : cycle) {
            min = Math.min(min, e.capacityFrom() / cumulativeRate);
            cumulativeRate *= e.rate();
        }
        return min;
    }
}
