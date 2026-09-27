package com.github.guoyyit.arbitragedetectorjava.model;

/**
 * One directed conversion step of an arbitrage cycle.
 *
 * @param market       market on which the conversion executes
 * @param from         source currency
 * @param to           destination currency
 * @param rate         gross conversion rate before fees (to per from)
 * @param capacityFrom max executable amount denominated in {@code from} currency
 */
public record Hop(String market, String from, String to, double rate, double capacityFrom) {
}
