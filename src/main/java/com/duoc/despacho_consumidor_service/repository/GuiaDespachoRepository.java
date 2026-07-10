package com.duoc.despacho_consumidor_service.repository;

import com.duoc.despacho_consumidor_service.entity.GuiaDespacho;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GuiaDespachoRepository extends JpaRepository<GuiaDespacho, Long> {

    Optional<GuiaDespacho> findByNumeroGuia(String numeroGuia);
}
