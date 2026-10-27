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
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "account_connection",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_account_connection_user_account", columnNames = ["app_user_id", "chess_account_id"]),
        UniqueConstraint(name = "uk_account_connection_user", columnNames = ["app_user_id"]),
    ],
)
class AccountConnection(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    val appUser: AppUser,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chess_account_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    val chessAccount: ChessAccount,
    @Column(name = "connected_at", nullable = false)
    val connectedAt: Instant = Instant.now(),
)
