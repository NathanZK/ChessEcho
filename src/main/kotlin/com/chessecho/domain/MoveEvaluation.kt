package com.chessecho.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "engine_move_evaluation")
class MoveEvaluation(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "engine_analysis_id", nullable = false)
    val engineAnalysis: EngineAnalysis,
    @Column(nullable = false)
    val move: String,
    @Column(name = "eval_cp")
    val evalCp: Int?,
    @Column(name = "eval_mate")
    val evalMate: Int?,
)
