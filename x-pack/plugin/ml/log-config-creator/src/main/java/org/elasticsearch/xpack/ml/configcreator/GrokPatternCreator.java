/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.configcreator;

import org.elasticsearch.grok.Grok;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Creates Grok patterns that will match all provided sample messages.
 *
 * The choice of field names is quite primitive.  The intention is that a human will edit these.
 */
public final class GrokPatternCreator {

    private static final Pattern PUNCTUATION_OR_SPACE = Pattern.compile("[\"'`‘’“”#@%=\\\\/|~:;,<>()\\[\\]{}«»^$*¿?¡!§¶ \t]");
    private static final Pattern NEEDS_ESCAPING = Pattern.compile("[\\\\|()\\[\\]{}^$*?]");

    private static final String PREFACE = "preface";
    private static final String EPILOGUE = "epilogue";

    /**
     * The first match in this list will be chosen, so it needs to be ordered
     * such that more generic patterns come after more specific patterns.
     */
    private static final List<GrokPatternCandidate> ORDERED_CANDIDATE_GROK_PATTERNS = Arrays.asList(
        new GrokPatternCandidate("TIMESTAMP_ISO8601", "date", "extra_timestamp"),
        new GrokPatternCandidate("DATESTAMP_RFC822", "date", "extra_timestamp"),
        new GrokPatternCandidate("DATESTAMP_RFC2822", "date", "extra_timestamp"),
        new GrokPatternCandidate("DATESTAMP_OTHER", "date", "extra_timestamp"),
        new GrokPatternCandidate("DATESTAMP_EVENTLOG", "date", "extra_timestamp"),
        new GrokPatternCandidate("SYSLOGTIMESTAMP", "date", "extra_timestamp"),
        new GrokPatternCandidate("HTTPDATE", "date", "extra_timestamp"),
        new GrokPatternCandidate("CATALINA_DATESTAMP", "date", "extra_timestamp"),
        new GrokPatternCandidate("TOMCAT_DATESTAMP", "date", "extra_timestamp"),
        new GrokPatternCandidate("CISCOTIMESTAMP", "date", "extra_timestamp"),
        new GrokPatternCandidate("DATE", "date", "date"),
        new GrokPatternCandidate("TIME", "date", "time"),
        new GrokPatternCandidate("LOGLEVEL", "keyword", "loglevel"),
        new GrokPatternCandidate("URI", "keyword", "uri"),
        new GrokPatternCandidate("UUID", "keyword", "uuid"),
        new GrokPatternCandidate("MAC", "keyword", "macaddress"),
        // Can't use \b as the breaks, because slashes are not "word" characters
        new GrokPatternCandidate("PATH", "keyword", "path", "(?<!\\w)", "(?!\\w)"),
        new GrokPatternCandidate("EMAILADDRESS", "keyword", "email"),
        // TODO: would be nice to have IPORHOST here, but HOST matches almost all words
        new GrokPatternCandidate("IP", "ip", "ipaddress"),
        // This already includes pre/post break conditions
        new GrokPatternCandidate("QUOTEDSTRING", "keyword", "field", "", ""),
        // Can't use \b as the break before, because it doesn't work for negative numbers (the
        // minus sign is not a "word" character)
        new GrokPatternCandidate("INT", "long", "field", "(?<![\\w.+-])", "(?![\\w.])"),
        new GrokPatternCandidate("NUMBER", "double", "field", "(?<![\\w.+-])"),
        // Disallow +, - and . before numbers, as well as "word" characters, otherwise we'll pick
        // up numeric suffices too eagerly
        new GrokPatternCandidate("BASE16NUM", "keyword", "field", "(?<![\\w.+-])")
        // TODO: also unfortunately can't have USERNAME in the list as it matches too broadly
        // Fixing these problems with overly broad matches would require some extra intelligence
        // to be added to remove inappropriate matches.  One idea would be to use a dictionary,
        // but that doesn't necessarily help as "jay" could be a username but is also a dictionary
        // word (plus there's the international headache with relying on dictionaries).  Similarly,
        // hostnames could also be dictionary words - I've worked on machines called "hippo" and
        // "scarf" in the past.  Another idea would be to look at the adjacent characters and
        // apply some heuristic based on those.
    );

    private GrokPatternCreator() {
    }

    public static String createGrokPatternFromExamples(Collection<String> sampleMessages, String seedPatternName, String seedFieldName,
                                                       Map<String, String> mappings) {

        GrokPatternCandidate seedCandidate = new GrokPatternCandidate(seedPatternName, null, seedFieldName);

        Map<String, Integer> fieldNameCountStore = new HashMap<>();
        StringBuilder overallGrokPatternBuilder = new StringBuilder();

        processCandidateAndSplit(fieldNameCountStore, overallGrokPatternBuilder, seedCandidate, seedFieldName, true, sampleMessages,
            mappings);

        return overallGrokPatternBuilder.toString();
    }

    /**
     * Given a chosen Grok pattern and a collection of message snippets, split the snippets into the
     * matched section and the pieces before and after it.  Recurse to find more matches in the pieces
     * before and after and update the supplied string builder.
     */
    private static void processCandidateAndSplit(Map<String, Integer> fieldNameCountStore, StringBuilder overallGrokPatternBuilder,
                                                 GrokPatternCandidate chosenPattern, String outputFieldName, boolean isLast,
                                                 Collection<String> snippets, Map<String, String> mappings) {

        Collection<String> prefaces = new ArrayList<>();
        Collection<String> epilogues = new ArrayList<>();
        populatePrefacesAndEpilogues(snippets, chosenPattern.grokPatternName, chosenPattern.grok, prefaces, epilogues);
        appendBestGrokMatchForStrings(fieldNameCountStore, overallGrokPatternBuilder, false, prefaces, mappings);
        overallGrokPatternBuilder.append("%{").append(chosenPattern.grokPatternName).append(':').append(outputFieldName).append('}');
        appendBestGrokMatchForStrings(fieldNameCountStore, overallGrokPatternBuilder, isLast, epilogues, mappings);
    }

    /**
     * Given a collection of message snippets, work out which (if any) of the Grok patterns we're allowed
     * to use matches it best.  Then append the appropriate Grok language to represent that finding onto
     * the supplied string builder.
     */
    static void appendBestGrokMatchForStrings(Map<String, Integer> fieldNameCountStore, StringBuilder overallGrokPatternBuilder,
                                              boolean isLast, Collection<String> snippets, Map<String, String> mappings) {

        GrokPatternCandidate bestCandidate = null;
        if (snippets.isEmpty() == false) {
            for (GrokPatternCandidate candidate : ORDERED_CANDIDATE_GROK_PATTERNS) {
                if (snippets.stream().allMatch(candidate.grok::match)) {
                    bestCandidate = candidate;
                    break;
                }
            }
        }

        if (bestCandidate == null) {
            if (isLast) {
                finalizeGrokPattern(overallGrokPatternBuilder, snippets);
            } else {
                addIntermediateRegex(overallGrokPatternBuilder, snippets);
            }
        } else {
            String outputFieldName = buildFieldName(fieldNameCountStore, bestCandidate.fieldName);
            mappings.put(outputFieldName, bestCandidate.mappingType);
            processCandidateAndSplit(fieldNameCountStore, overallGrokPatternBuilder, bestCandidate, outputFieldName, isLast, snippets,
                mappings);
        }
    }

    /**
     * Given a collection of strings, and a Grok pattern that matches some part of them all,
     * return collections of the bits that come before (prefaces) and after (epilogues) the
     * bit that matches.
     */
    static void populatePrefacesAndEpilogues(Collection<String> snippets, String grokPatternName, Grok grok, Collection<String> prefaces,
                                             Collection<String> epilogues) {
        for (String snippet : snippets) {
            Map<String, Object> captures = grok.captures(snippet);
            // If the pattern doesn't match then captures will be null
            if (captures == null) {
                throw new IllegalStateException("[" + grokPatternName + "] does not match snippet [" + snippet + "]");
            }
            prefaces.add(captures.getOrDefault(PREFACE, "").toString());
            epilogues.add(captures.getOrDefault(EPILOGUE, "").toString());
        }
    }

    /**
     * The first time a particular field name is passed, simply return it.
     * The second time return it with "2" appended.
     * The third time return it with "3" appended.
     * Etc.
     */
    static String buildFieldName(Map<String, Integer> fieldNameCountStore, String fieldName) {
        Integer numberSeen = fieldNameCountStore.compute(fieldName, (k, v) -> 1 + ((v == null) ? 0 : v));
        return (numberSeen > 1) ? fieldName + numberSeen : fieldName;
    }

    public static void addIntermediateRegex(StringBuilder overallPatternBuilder, Collection<String> snippets) {
        if (snippets.isEmpty()) {
            return;
        }

        List<String> others = new ArrayList<>(snippets);
        String driver = others.remove(others.size() - 1);

        boolean wildcardRequired = true;
        for (int i = 0; i < driver.length(); ++i) {
            char ch = driver.charAt(i);
            String chAsString = String.valueOf(ch);
            if (PUNCTUATION_OR_SPACE.matcher(chAsString).matches() && others.stream().allMatch(other -> other.indexOf(ch) >= 0)) {
                if (wildcardRequired && others.stream().anyMatch(other -> other.indexOf(ch) > 0)) {
                    overallPatternBuilder.append(".*?");
                }
                if (NEEDS_ESCAPING.matcher(chAsString).matches()) {
                    overallPatternBuilder.append('\\');
                }
                overallPatternBuilder.append(ch);
                wildcardRequired = true;
                others = others.stream().map(other -> other.substring(other.indexOf(ch) + 1)).collect(Collectors.toList());
            } else if (wildcardRequired) {
                overallPatternBuilder.append(".*?");
                wildcardRequired = false;
            }
        }

        if (wildcardRequired && others.stream().allMatch(String::isEmpty) == false) {
            overallPatternBuilder.append(".*?");
        }
    }

    private static void finalizeGrokPattern(StringBuilder overallPatternBuilder, Collection<String> snippets) {
        if (snippets.stream().allMatch(String::isEmpty)) {
            return;
        }

        List<String> others = new ArrayList<>(snippets);
        String driver = others.remove(others.size() - 1);

        for (int i = 0; i < driver.length(); ++i) {
            char ch = driver.charAt(i);
            String chAsString = String.valueOf(ch);
            int driverIndex = i;
            if (PUNCTUATION_OR_SPACE.matcher(chAsString).matches() &&
                others.stream().allMatch(other -> other.length() > driverIndex && other.charAt(driverIndex) == ch)) {
                if (NEEDS_ESCAPING.matcher(chAsString).matches()) {
                    overallPatternBuilder.append('\\');
                }
                overallPatternBuilder.append(ch);
                if (i == driver.length() - 1 && others.stream().allMatch(driver::equals)) {
                    return;
                }
            } else {
                break;
            }
        }

        overallPatternBuilder.append(".*");
    }

    static class GrokPatternCandidate {

        final String grokPatternName;
        final String mappingType;
        final String fieldName;
        final Grok grok;

        /**
         * Pre/post breaks default to \b, but this may not be appropriate for Grok patterns that start or
         * end with a non "word" character (i.e. letter, number or underscore).  For such patterns use one
         * of the other constructors.
         *
         * In cases where the Grok pattern defined by Logstash already includes conditions on what must
         * come before and after the match, use one of the other constructors and specify an empty string
         * for the pre and/or post breaks.
         * @param grokPatternName Name of the Grok pattern to try to match - must match one defined in Logstash.
         * @param fieldName       Name of the field to extract from the match.
         */
        GrokPatternCandidate(String grokPatternName, String mappingType, String fieldName) {
            this(grokPatternName, mappingType, fieldName, "\\b", "\\b");
        }

        GrokPatternCandidate(String grokPatternName, String mappingType, String fieldName, String preBreak) {
            this(grokPatternName, mappingType, fieldName, preBreak, "\\b");
        }

        /**
         * @param grokPatternName Name of the Grok pattern to try to match - must match one defined in Logstash.
         * @param mappingType     Data type for field in Elasticsearch mappings.
         * @param fieldName       Name of the field to extract from the match.
         * @param preBreak        Only consider the match if it's broken from the previous text by this.
         * @param postBreak       Only consider the match if it's broken from the following text by this.
         */
        GrokPatternCandidate(String grokPatternName, String mappingType, String fieldName, String preBreak, String postBreak) {
            this.grokPatternName = grokPatternName;
            this.mappingType = mappingType;
            this.fieldName = fieldName;
            this.grok = new Grok(Grok.getBuiltinPatterns(), "%{DATA:" + PREFACE + "}" + preBreak + "%{" + grokPatternName + ":this}" +
                postBreak + "%{GREEDYDATA:" + EPILOGUE + "}");
        }
    }
}
