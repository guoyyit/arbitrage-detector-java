package com.github.guoyyit.arbitragedetectorjava.model;

import java.util.List;

/**
 * A profitable cycle detected on one consistent quote snapshot.
 *
 * @param signature       canonical identity of the cycle (stable across re-detection)
 * @param hops            ordered conversion steps, last hop returns to the first currency
 * @param grossReturn     product of hop rates minus 1
 * @param netReturn       gross return after per-hop fee and slippage
 * @param maxVolume       max executable start amount (in start currency) limited by hop capacities
 * @param snapshotVersion version of the quote snapshot the detection ran on
 * @param detectedAt      detection wall-clock time, epoch millis
 */
public record ArbitrageOpportunity(String signature, List<Hop> hops,
                                   double grossReturn, double netReturn,
                                   double maxVolume, long snapshotVersion,
                                   long detectedAt) {

    public String startCurrency() {
        return hops.get(0).from();
    }
}
