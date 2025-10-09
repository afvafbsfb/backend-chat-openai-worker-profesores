package com.workers.profesores.chat.auth;

import java.util.List;

public class UserClaims {
    public final Long usuarioId;
    public final List<String> roles;
    public final Integer academiaId;
    public final Integer profesorUsuarioId;

    public UserClaims(Long usuarioId, List<String> roles, Integer academiaId, Integer profesorUsuarioId) {
        this.usuarioId = usuarioId;
        this.roles = roles;
        this.academiaId = academiaId;
        this.profesorUsuarioId = profesorUsuarioId;
    }

    public boolean isAdminPlataforma() { return roles != null && roles.contains("Admin_plataforma"); }
    public boolean isAdminAcademia() { return roles != null && roles.contains("Admin_academia"); }
    public boolean isProfesorAcademia() { return roles != null && roles.contains("Profesor_academia"); }
}
