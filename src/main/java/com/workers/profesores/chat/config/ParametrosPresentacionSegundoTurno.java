package com.workers.profesores.chat.config;

/**
 * Agrupa parámetros de presentación/formato del segundo turno.
 * No influyen en la toma de decisiones del árbol, solo en cómo construimos
 * el descriptor y el schema de response_format.
 */
public class ParametrosPresentacionSegundoTurno {
    // Descriptor (muestras, truncado)
    private int fieldsScanLimit = 6;
    private int sampleItemsMax = 1;
    private int truncateStringLength = 30;
    private boolean obfuscateEmails = true;

    // Schema LITE (response_format)
    private int textMaxLength = 400;
    private int suggestionsMin = 2;
    private int suggestionsMax = 5;
    private int suggestionItemMaxLen = 120;
    private int summaryFieldsMin = 1;
    private int summaryFieldsMax = 2;

    // Getters
    public int getFieldsScanLimit() { return fieldsScanLimit; }
    public int getSampleItemsMax() { return sampleItemsMax; }
    public int getTruncateStringLength() { return truncateStringLength; }
    public boolean isObfuscateEmails() { return obfuscateEmails; }
    public int getTextMaxLength() { return textMaxLength; }
    public int getSuggestionsMin() { return suggestionsMin; }
    public int getSuggestionsMax() { return suggestionsMax; }
    public int getSuggestionItemMaxLen() { return suggestionItemMaxLen; }
    public int getSummaryFieldsMin() { return summaryFieldsMin; }
    public int getSummaryFieldsMax() { return summaryFieldsMax; }

    // Fluent setters (para tests/config programática)
    public ParametrosPresentacionSegundoTurno setFieldsScanLimit(int v) { this.fieldsScanLimit = v; return this; }
    public ParametrosPresentacionSegundoTurno setSampleItemsMax(int v) { this.sampleItemsMax = v; return this; }
    public ParametrosPresentacionSegundoTurno setTruncateStringLength(int v) { this.truncateStringLength = v; return this; }
    public ParametrosPresentacionSegundoTurno setObfuscateEmails(boolean v) { this.obfuscateEmails = v; return this; }
    public ParametrosPresentacionSegundoTurno setTextMaxLength(int v) { this.textMaxLength = v; return this; }
    public ParametrosPresentacionSegundoTurno setSuggestionsMin(int v) { this.suggestionsMin = v; return this; }
    public ParametrosPresentacionSegundoTurno setSuggestionsMax(int v) { this.suggestionsMax = v; return this; }
    public ParametrosPresentacionSegundoTurno setSuggestionItemMaxLen(int v) { this.suggestionItemMaxLen = v; return this; }
    public ParametrosPresentacionSegundoTurno setSummaryFieldsMin(int v) { this.summaryFieldsMin = v; return this; }
    public ParametrosPresentacionSegundoTurno setSummaryFieldsMax(int v) { this.summaryFieldsMax = v; return this; }
}
