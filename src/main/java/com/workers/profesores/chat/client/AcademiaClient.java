package com.workers.profesores.chat.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AcademiaClient {
    @org.springframework.beans.factory.annotation.Value("${backend.debug:false}")
    private boolean debug;


    @Value("${academia.api.key}")
    private String apiKey;
    // --- Métodos generados a partir de la whitelist ---
    public JsonNode healthCheck() { if (debug) System.out.println("[AcademiaClient][DEBUG] healthCheck()"); return null; }
    public JsonNode getTurnosLibres() { if (debug) System.out.println("[AcademiaClient][DEBUG] getTurnosLibres()"); return null; }
    public JsonNode inscribirAlumno(JsonNode body) { if (debug) System.out.println("[AcademiaClient][DEBUG] inscribirAlumno body=" + body); return null; }
    public JsonNode registrarPago(JsonNode body) { if (debug) System.out.println("[AcademiaClient][DEBUG] registrarPago body=" + body); return null; }
    public JsonNode getTurnosActivos() { if (debug) System.out.println("[AcademiaClient][DEBUG] getTurnosActivos()"); return null; }
    public JsonNode getTarifas() { if (debug) System.out.println("[AcademiaClient][DEBUG] getTarifas()"); return null; }
    public JsonNode crearTarifa(JsonNode body) { if (debug) System.out.println("[AcademiaClient][DEBUG] crearTarifa body=" + body); return null; }
    public JsonNode actualizarTarifa(String tarifa_id, JsonNode body) { if (debug) System.out.println("[AcademiaClient][DEBUG] actualizarTarifa tarifa_id=" + tarifa_id + ", body=" + body); return null; }
    public JsonNode eliminarTarifa(String tarifa_id) { if (debug) System.out.println("[AcademiaClient][DEBUG] eliminarTarifa tarifa_id=" + tarifa_id); return null; }
    public JsonNode getAlumnosTurno(String turno_id) { if (debug) System.out.println("[AcademiaClient][DEBUG] getAlumnosTurno turno_id=" + turno_id); return null; }
    public JsonNode bajaInscripcion(String inscripcion_id) { if (debug) System.out.println("[AcademiaClient][DEBUG] bajaInscripcion inscripcion_id=" + inscripcion_id); return null; }
    public JsonNode getAlumnos() { if (debug) System.out.println("[AcademiaClient][DEBUG] getAlumnos()"); return null; }
    public JsonNode getAlumnoById(String alumno_id) { if (debug) System.out.println("[AcademiaClient][DEBUG] getAlumnoById alumno_id=" + alumno_id); return null; }
    public JsonNode buscarAlumnosPorNombre(String nombre) { if (debug) System.out.println("[AcademiaClient][DEBUG] buscarAlumnosPorNombre nombre=" + nombre); return null; }
    public JsonNode crearEmpresa(JsonNode body) { if (debug) System.out.println("[AcademiaClient][DEBUG] crearEmpresa body=" + body); return null; }
    public JsonNode getEmpresas() { if (debug) System.out.println("[AcademiaClient][DEBUG] getEmpresas()"); return null; }
    public JsonNode getEmpresaById(String empresa_id) { if (debug) System.out.println("[AcademiaClient][DEBUG] getEmpresaById empresa_id=" + empresa_id); return null; }
}
