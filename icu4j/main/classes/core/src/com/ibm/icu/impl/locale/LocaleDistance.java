// © 2017 and later: Unicode, Inc. and others.
// License & terms of use: http://www.unicode.org/copyright.html#License
package com.ibm.icu.impl.locale;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.ibm.icu.impl.locale.XLocaleMatcher.FavorSubtag;
import com.ibm.icu.util.BytesTrie;
import com.ibm.icu.util.ULocale;

/**
 * Off-line-built data for LocaleMatcher.
 * Mostly but not only the data for mapping locales to their maximized forms.
 */
public class LocaleDistance {
    /** Distance value bit flag, set by the builder. */
    static final int DISTANCE_SKIP_SCRIPT = 0x80;
    /** Distance value bit flag, set by trieNext(). */
    private static final int DISTANCE_IS_FINAL = 0x100;
    private static final int DISTANCE_IS_FINAL_OR_SKIP_SCRIPT =
            DISTANCE_IS_FINAL | DISTANCE_SKIP_SCRIPT;
    // Indexes into array of distances.
    static final int IX_DEF_LANG_DISTANCE = 0;
    static final int IX_DEF_SCRIPT_DISTANCE = 1;
    static final int IX_DEF_REGION_DISTANCE = 2;
    static final int IX_MIN_REGION_DISTANCE = 3;
    static final int IX_LIMIT = 4;
    private static final int ABOVE_THRESHOLD = 100;

    private static final boolean DEBUG_OUTPUT = LSR.DEBUG_OUTPUT;

    // The trie maps each dlang+slang+dscript+sscript+dregion+sregion
    // (encoded in ASCII with bit 7 set on the last character of each subtag) to a distance.
    // There is also a trie value for each subsequence of whole subtags.
    // One '*' is used for a (desired, supported) pair of "und", "Zzzz"/"", or "ZZ"/"".
    private final BytesTrie trie;

    /**
     * Maps each region to zero or more single-character partitions.
     */
    private final byte[] regionToPartitionsIndex;
    private final String[] partitionArrays;

    /**
     * Used to get the paradigm region for a cluster, if there is one.
     */
    private final Set<LSR> paradigmLSRs;

    private final int defaultLanguageDistance;
    private final int defaultScriptDistance;
    private final int defaultRegionDistance;
    private final int minRegionDistance;
    private final int defaultDemotionPerDesiredLocale;

    // TODO: Load prebuilt data from a resource bundle
    // to avoid the dependency on the builder code.
    // VisibleForTesting
    public static final LocaleDistance INSTANCE = LocaleDistanceBuilder.build();

    LocaleDistance(BytesTrie trie,
            byte[] regionToPartitionsIndex, String[] partitionArrays,
            Set<LSR> paradigmLSRs, int[] distances) {
        this.trie = trie;
        this.regionToPartitionsIndex = regionToPartitionsIndex;
        this.partitionArrays = partitionArrays;
        this.paradigmLSRs = paradigmLSRs;
        defaultLanguageDistance = distances[IX_DEF_LANG_DISTANCE];
        defaultScriptDistance = distances[IX_DEF_SCRIPT_DISTANCE];
        defaultRegionDistance = distances[IX_DEF_REGION_DISTANCE];
        this.minRegionDistance = distances[IX_MIN_REGION_DISTANCE];

        LSR en = new LSR("en", "Latn", "US");
        LSR enGB = new LSR("en", "Latn", "GB");
        defaultDemotionPerDesiredLocale = getBestIndexAndDistance(en, new LSR[] { enGB },
                50, FavorSubtag.LANGUAGE) & 0xff;

        if (DEBUG_OUTPUT) {
            System.out.println("*** locale distance");
            System.out.println("defaultLanguageDistance=" + defaultLanguageDistance);
            System.out.println("defaultScriptDistance=" + defaultScriptDistance);
            System.out.println("defaultRegionDistance=" + defaultRegionDistance);
            testOnlyPrintDistanceTable();
        }
    }

    // VisibleForTesting
    public int testOnlyDistance(ULocale desired, ULocale supported,
            int threshold, FavorSubtag favorSubtag) {
        LSR supportedLSR = XLikelySubtags.INSTANCE.makeMaximizedLsrFrom(supported);
        LSR desiredLSR = XLikelySubtags.INSTANCE.makeMaximizedLsrFrom(desired);
        return getBestIndexAndDistance(desiredLSR, new LSR[] { supportedLSR },
                threshold, favorSubtag) & 0xff;
    }

    /**
     * Finds the supported LSR with the smallest distance from the desired one.
     * Equivalent LSR subtags must be normalized into a canonical form.
     *
     * <p>Returns the index of the lowest-distance supported LSR in bits 31..8
     * (negative if none has a distance below the threshold),
     * and its distance (0..ABOVE_THRESHOLD) in bits 7..0.
     */
    int getBestIndexAndDistance(LSR desired, LSR[] supportedLsrs,
            int threshold, FavorSubtag favorSubtag) {
        BytesTrie iter = new BytesTrie(trie);
        // Look up the desired language only once for all supported LSRs.
        // Its "distance" is either a match point value of 0, or a non-match negative value.
        // Note: The data builder verifies that there are no <*, supported> or <desired, *> rules.
        int desLangDistance = trieNext(iter, desired.language, false);
        long desLangState = desLangDistance >= 0 && supportedLsrs.length > 1 ? iter.getState64() : 0;
        // Index of the supported LSR with the lowest distance.
        int bestIndex = -1;
        for (int slIndex = 0; slIndex < supportedLsrs.length; ++slIndex) {
            LSR supported = supportedLsrs[slIndex];
            boolean star = false;
            int distance = desLangDistance;
            if (distance >= 0) {
                assert (distance & DISTANCE_IS_FINAL) == 0;
                if (slIndex != 0) {
                    iter.resetToState64(desLangState);
                }
                distance = trieNext(iter, supported.language, true);
            }
            // Note: The data builder verifies that there are no rules with "any" (*) language and
            // real (non *) script or region subtags.
            // This means that if the lookup for either language fails we can use
            // the default distances without further lookups.
            int flags;
            if (distance >= 0) {
                flags = distance & DISTANCE_IS_FINAL_OR_SKIP_SCRIPT;
                distance &= ~DISTANCE_IS_FINAL_OR_SKIP_SCRIPT;
            } else {  // <*, *>
                if (desired.language.equals(supported.language)) {
                    distance = 0;
                } else {
                    distance = defaultLanguageDistance;
                }
                flags = 0;
                star = true;
            }
            assert 0 <= distance && distance <= 100;
            if (favorSubtag == FavorSubtag.SCRIPT) {
                distance >>= 2;
            }
            if (distance >= threshold) {
                continue;
            }

            int scriptDistance;
            if (star || flags != 0) {
                if (desired.script.equals(supported.script)) {
                    scriptDistance = 0;
                } else {
                    scriptDistance = defaultScriptDistance;
                }
            } else {
                scriptDistance = getDesSuppScriptDistance(iter, iter.getState64(),
                        desired.script, supported.script);
                flags = scriptDistance & DISTANCE_IS_FINAL;
                scriptDistance &= ~DISTANCE_IS_FINAL;
            }
            distance += scriptDistance;
            if (distance >= threshold) {
                continue;
            }

            if (desired.region.equals(supported.region)) {
                // regionDistance = 0
            } else if (star || (flags & DISTANCE_IS_FINAL) != 0) {
                distance += defaultRegionDistance;
            } else {
                int remainingThreshold = threshold - distance;
                if (minRegionDistance >= remainingThreshold) {
                    continue;
                }

                // From here on we know the regions are not equal.
                // Map each region to zero or more partitions. (zero = one non-matching string)
                // (Each array of single-character partition strings is encoded as one string.)
                // If either side has more than one, then we find the maximum distance.
                // This could be optimized by adding some more structure, but probably not worth it.
                distance += getRegionPartitionsDistance(
                        iter, iter.getState64(),
                        partitionsForRegion(desired),
                        partitionsForRegion(supported),
                        remainingThreshold);
            }
            if (distance < threshold) {
                if (distance == 0) {
                    return slIndex << 8;
                }
                bestIndex = slIndex;
                threshold = distance;
            }
        }
        return bestIndex >= 0 ? (bestIndex << 8) | threshold : 0xffffff00 | ABOVE_THRESHOLD;
    }

    private static final int getDesSuppScriptDistance(BytesTrie iter, long startState,
            String desired, String supported) {
        // Note: The data builder verifies that there are no <*, supported> or <desired, *> rules.
        int distance = trieNext(iter, desired, false);
        if (distance >= 0) {
            distance = trieNext(iter, supported, true);
        }
        if (distance < 0) {
            BytesTrie.Result result = iter.resetToState64(startState).next('*');  // <*, *>
            assert result.hasValue();
            if (desired.equals(supported)) {
                distance = 0;  // same script
            } else {
                distance = iter.getValue();
                assert distance >= 0;
            }
            if (result == BytesTrie.Result.FINAL_VALUE) {
                distance |= DISTANCE_IS_FINAL;
            }
        }
        return distance;
    }

    private static final int getRegionPartitionsDistance(BytesTrie iter, long startState,
            String desiredPartitions, String supportedPartitions, int threshold) {
        int desLength = desiredPartitions.length();
        int suppLength = supportedPartitions.length();
        if (desLength == 1 && suppLength == 1) {
            BytesTrie.Result result = iter.next(desiredPartitions.charAt(0) | 0x80);
            if (result.hasNext()) {
                result = iter.next(supportedPartitions.charAt(0) | 0x80);
                if (result.hasValue()) {
                    return iter.getValue();
                }
            }
            return getFallbackRegionDistance(iter, startState);
        }

        int regionDistance = 0;
        // Fall back to * only once, not for each pair of partition strings.
        boolean star = false;
        for (int di = 0;;) {
            // Look up each desired-partition string only once,
            // not for each (desired, supported) pair.
            BytesTrie.Result result = iter.next(desiredPartitions.charAt(di++) | 0x80);
            if (result.hasNext()) {
                long desState = suppLength > 1 ? iter.getState64() : 0;
                for (int si = 0;;) {
                    result = iter.next(supportedPartitions.charAt(si++) | 0x80);
                    int d;
                    if (result.hasValue()) {
                        d = iter.getValue();
                    } else if (star) {
                        d = 0;
                    } else {
                        d = getFallbackRegionDistance(iter, startState);
                        star = true;
                    }
                    if (d >= threshold) {
                        return d;
                    } else if (regionDistance < d) {
                        regionDistance = d;
                    }
                    if (si < suppLength) {
                        iter.resetToState64(desState);
                    } else {
                        break;
                    }
                }
            } else if (!star) {
                int d = getFallbackRegionDistance(iter, startState);
                if (d >= threshold) {
                    return d;
                } else if (regionDistance < d) {
                    regionDistance = d;
                }
                star = true;
            }
            if (di < desLength) {
                iter.resetToState64(startState);
            } else {
                break;
            }
        }
        return regionDistance;
    }

    private static final int getFallbackRegionDistance(BytesTrie iter, long startState) {
        BytesTrie.Result result = iter.resetToState64(startState).next('*');  // <*, *>
        assert result.hasValue();
        int distance = iter.getValue();
        assert distance >= 0;
        return distance;
    }

    private static final int trieNext(BytesTrie iter, String s, boolean wantValue) {
        if (s.isEmpty()) {
            return -1;  // no empty subtags in the distance data
        }
        for (int i = 0, end = s.length() - 1;; ++i) {
            int c = s.charAt(i);
            if (i < end) {
                if (!iter.next(c).hasNext()) {
                    return -1;
                }
            } else {
                // last character of this subtag
                BytesTrie.Result result = iter.next(c | 0x80);
                if (wantValue) {
                    if (result.hasValue()) {
                        int value = iter.getValue();
                        if (result == BytesTrie.Result.FINAL_VALUE) {
                            value |= DISTANCE_IS_FINAL;
                        }
                        return value;
                    }
                } else {
                    if (result.hasNext()) {
                        return 0;
                    }
                }
                return -1;
            }
        }
    }

    @Override
    public String toString() {
        return testOnlyGetDistanceTable().toString();
    }

    private String partitionsForRegion(LSR lsr) {
        // ill-formed region -> one non-matching string
        int pIndex = regionToPartitionsIndex[lsr.regionIndex];
        return partitionArrays[pIndex];
    }

    boolean isParadigmLSR(LSR lsr) {
        return paradigmLSRs.contains(lsr);
    }

    // VisibleForTesting
    public int getDefaultScriptDistance() {
        return defaultScriptDistance;
    }

    int getDefaultRegionDistance() {
        return defaultRegionDistance;
    }

    int getDefaultDemotionPerDesiredLocale() {
        return defaultDemotionPerDesiredLocale;
    }

    // TODO: When we build data offline,
    // write test code to compare the loaded table with the builder output.
    // Fail if different, with instructions for how to update the data file.
    // VisibleForTesting
    public Map<String, Integer> testOnlyGetDistanceTable() {
        Map<String, Integer> map = new TreeMap<>();
        StringBuilder sb = new StringBuilder();
        for (BytesTrie.Entry entry : trie) {
            sb.setLength(0);
            int length = entry.bytesLength();
            for (int i = 0; i < length; ++i) {
                byte b = entry.byteAt(i);
                if (b == '*') {
                    // One * represents a (desired, supported) = (ANY, ANY) pair.
                    sb.append("*-*-");
                } else {
                    if (b >= 0) {
                        sb.append((char) b);
                    } else {  // end of subtag
                        sb.append((char) (b & 0x7f)).append('-');
                    }
                }
            }
            assert sb.length() > 0 && sb.charAt(sb.length() - 1) == '-';
            sb.setLength(sb.length() - 1);
            map.put(sb.toString(), entry.value);
        }
        return map;
    }

    // VisibleForTesting
    public void testOnlyPrintDistanceTable() {
        for (Map.Entry<String, Integer> mapping : testOnlyGetDistanceTable().entrySet()) {
            String suffix = "";
            int value = mapping.getValue();
            if ((value & DISTANCE_SKIP_SCRIPT) != 0) {
                value &= ~DISTANCE_SKIP_SCRIPT;
                suffix = " skip script";
            }
            System.out.println(mapping.getKey() + '=' + value + suffix);
        }
    }
}