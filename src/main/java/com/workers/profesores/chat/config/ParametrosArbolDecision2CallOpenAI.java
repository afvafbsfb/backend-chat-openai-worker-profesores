package com.workers.profesores.chat.config;

import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Centraliza TODOS los parámetros/constantes del árbol de decisión del 2º turno (OpenAI).
 * Bean de configuración enlazado a application.properties bajo el prefijo "chat".
 */
@Component
@ConfigurationProperties(prefix = "chat")
public class ParametrosArbolDecision2CallOpenAI {

    public static class HeuristicRule {
        private final String target;
        private final List<String> anyOf; // si alguno aparece, cuenta
        private final List<String> allOf; // deben aparecer todos (si no está vacío)
        private final int priority; // menor = más prioridad

        public HeuristicRule(String target, List<String> anyOf, List<String> allOf, int priority) {
            this.target = target;
            this.anyOf = anyOf == null ? List.of() : List.copyOf(anyOf);
            this.allOf = allOf == null ? List.of() : List.copyOf(allOf);
            this.priority = priority;
        }
        public String getTarget() { return target; }
        public List<String> getAnyOf() { return anyOf; }
        public List<String> getAllOf() { return allOf; }
        public int getPriority() { return priority; }
    }

    // Flags/prerrequisitos del modo LITE y fast-path (DECISIÓN)
    private boolean secondTurnLiteEnabled = false;
    private boolean fastpathEnabled = true;
    private boolean requireSingleToolCall = true;
    private List<String> allowedMethodsForLite = List.of("GET");

    // Targets soportados (plurales) (DECISIÓN)
    private List<String> allowedTargetsPlural = List.of("usuarios","academias","cursos","alumnos","profesores");

    // Claves de arrays genéricos y específicos por target para encontrar listados (DECISIÓN)
    private List<String> genericArrayKeys = List.of("items");
    private Map<String, List<String>> targetSpecificArrayKeys = Map.of(
        "academias", List.of("result")
    );

    // Heurística de inferencia de target por campos presentes (DECISIÓN)
    private List<HeuristicRule> heuristicRules = List.of(
        // Prioridad 1: usuarios si vemos email o nombre
        new HeuristicRule("usuarios", List.of("email","nombre"), List.of(), 1),
        // Prioridad 2: cursos por nombre o anio_academico
        new HeuristicRule("cursos", List.of("nombre","anio_academico"), List.of(), 2),
        // Prioridad 3: academias por nombre y direccion
        new HeuristicRule("academias", List.of(), List.of("nombre","direccion"), 3)
    );

    // Heurística: límite de items a inspeccionar (DECISIÓN)
    private int heuristicScanLimit = 5;

    // Tipo por defecto cuando no se detecta target (DECISIÓN)
    private String defaultType = "chat";

    // Mapeo singular->plural para parseo de respuestas con objetos singulares (DECISIÓN)
    private Map<String, String> singularToPlural = Map.of(
        "usuario","usuarios",
        "academia","academias",
        "curso","cursos",
        "alumno","alumnos",
        "profesor","profesores"
    );

    // Targets con fast-path permitido (DECISIÓN)
    private List<String> fastpathTargetsAllowed = List.of("usuarios","academias");

    // Presupuesto y límites del segundo turno (CONFIG)
    private long secondTurnBudgetMs = 15000L; // antes 8000
    private int secondTurnMaxExtraIterations = 3; // antes 2
    // Fast-path previo al 2º turno bajo flag explícito (CONFIG)
    private boolean preSecondFastpathEnabled = false;
    // Enriquecimiento de presentación (backend) por defecto desactivado
    private boolean presentationEnrichmentEnabled = false;
    // Muestra máxima de ítems a reinyectar al 2º turno (eco recortado)
    private int modelEchoSampleSize = 5;

    // Getters
    public boolean isSecondTurnLiteEnabled() { return secondTurnLiteEnabled; }
    public boolean isFastpathEnabled() { return fastpathEnabled; }
    public boolean isRequireSingleToolCall() { return requireSingleToolCall; }
    public List<String> getAllowedMethodsForLite() { return allowedMethodsForLite; }
    public List<String> getAllowedTargetsPlural() { return allowedTargetsPlural; }
    public List<String> getGenericArrayKeys() { return genericArrayKeys; }
    public Map<String, List<String>> getTargetSpecificArrayKeys() { return targetSpecificArrayKeys; }
    public List<HeuristicRule> getHeuristicRules() { return heuristicRules; }
    public int getHeuristicScanLimit() { return heuristicScanLimit; }
    public String getDefaultType() { return defaultType; }
    public Map<String, String> getSingularToPlural() { return singularToPlural; }
    public List<String> getFastpathTargetsAllowed() { return fastpathTargetsAllowed; }
    public long getSecondTurnBudgetMs() { return secondTurnBudgetMs; }
    public int getSecondTurnMaxExtraIterations() { return secondTurnMaxExtraIterations; }
    public boolean isPreSecondFastpathEnabled() { return preSecondFastpathEnabled; }
    public boolean isPresentationEnrichmentEnabled() { return presentationEnrichmentEnabled; }
    public int getModelEchoSampleSize() { return modelEchoSampleSize; }

    // Setters opcionales para tests o configuración programática
    public ParametrosArbolDecision2CallOpenAI setSecondTurnLiteEnabled(boolean v) { this.secondTurnLiteEnabled = v; return this; }
    public ParametrosArbolDecision2CallOpenAI setFastpathEnabled(boolean v) { this.fastpathEnabled = v; return this; }
    public ParametrosArbolDecision2CallOpenAI setRequireSingleToolCall(boolean v) { this.requireSingleToolCall = v; return this; }
    public ParametrosArbolDecision2CallOpenAI setAllowedMethodsForLite(List<String> v) { this.allowedMethodsForLite = List.copyOf(v); return this; }
    public ParametrosArbolDecision2CallOpenAI setAllowedTargetsPlural(List<String> v) { this.allowedTargetsPlural = List.copyOf(v); return this; }
    public ParametrosArbolDecision2CallOpenAI setGenericArrayKeys(List<String> v) { this.genericArrayKeys = List.copyOf(v); return this; }
    public ParametrosArbolDecision2CallOpenAI setTargetSpecificArrayKeys(Map<String,List<String>> v) { this.targetSpecificArrayKeys = Map.copyOf(v); return this; }
    public ParametrosArbolDecision2CallOpenAI setHeuristicRules(List<HeuristicRule> v) { this.heuristicRules = List.copyOf(v); return this; }
    public ParametrosArbolDecision2CallOpenAI setHeuristicScanLimit(int v) { this.heuristicScanLimit = v; return this; }
    public ParametrosArbolDecision2CallOpenAI setDefaultType(String v) { this.defaultType = v; return this; }
    public ParametrosArbolDecision2CallOpenAI setSingularToPlural(Map<String,String> v) { this.singularToPlural = Map.copyOf(v); return this; }
    public ParametrosArbolDecision2CallOpenAI setFastpathTargetsAllowed(List<String> v) { this.fastpathTargetsAllowed = List.copyOf(v); return this; }
    public ParametrosArbolDecision2CallOpenAI setSecondTurnBudgetMs(long v) { this.secondTurnBudgetMs = v; return this; }
    public ParametrosArbolDecision2CallOpenAI setSecondTurnMaxExtraIterations(int v) { this.secondTurnMaxExtraIterations = v; return this; }
    public ParametrosArbolDecision2CallOpenAI setPreSecondFastpathEnabled(boolean v) { this.preSecondFastpathEnabled = v; return this; }
    public ParametrosArbolDecision2CallOpenAI setPresentationEnrichmentEnabled(boolean v) { this.presentationEnrichmentEnabled = v; return this; }
    public ParametrosArbolDecision2CallOpenAI setModelEchoSampleSize(int v) { this.modelEchoSampleSize = v; return this; }
}
