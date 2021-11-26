package io.github.monun.speculation.game

import io.github.monun.speculation.game.dialog.GameDialog
import io.github.monun.speculation.game.dialog.GameDialogSeizure
import io.github.monun.speculation.game.event.PieceDepositEvent
import io.github.monun.speculation.game.event.PieceMoveEvent
import io.github.monun.speculation.game.event.PieceTransferEvent
import io.github.monun.speculation.game.event.PieceWithdrawEvent
import io.github.monun.speculation.game.exception.BankruptException
import io.github.monun.speculation.game.message.GameMessage
import io.github.monun.speculation.game.zone.Zone
import io.github.monun.speculation.game.zone.ZoneProperty
import io.github.monun.speculation.ref.upstream
import kotlin.math.min

class Piece(board: Board, val name: String, zone: Zone) {
    val board = upstream(board)

    var team: PieceTeam? = null

    val hasTurn: Boolean
        get() = board.game.currentTurn === this

    var balance = 400
        private set

    var isBankrupt = false
        private set

    var zone: Zone = zone
        private set

    val properties: List<ZoneProperty>
        get() = board.zoneProperties.filter { it.owner == this }

    val assets: Int
        get() = properties.sumOf { it.assets }

    fun isFriendly(piece: Piece): Boolean {
        if (piece === this) return true

        val team = team
        return team != null && team === piece.team
    }

    internal suspend fun <R> request(dialog: GameDialog<R>, message: GameMessage, default: () -> R): R {
        return board.game.dialogAdapter.request(dialog.apply {
            initialize(this@Piece, message, default)
        })
    }

    internal suspend fun moveTo(destination: Zone, movement: Movement, cause: MovementCause, source: Piece) {
        val journey = Journey(this, zone, destination, movement, cause, source).apply {
            pathfinding()
        }

        journey.from.onLeave(journey)

        for (zone in journey.path) {
            this.zone = zone
            zone.onPass(journey)
            journey.pass(zone)
            board.game.eventAdapter.call(PieceMoveEvent(this, journey, zone))
        }

        zone = journey.destination
        zone.onArrive(journey)
        board.game.eventAdapter.call(PieceMoveEvent(this, journey, zone))
    }

    internal suspend fun deposit(amount: Int, source: Any) {
        balance += amount
        board.game.eventAdapter.call(PieceDepositEvent(this, amount, source))
    }

    private fun selectProperties(total: Int, requestAmount: Int): Pair<List<ZoneProperty>, List<ZoneProperty>> {
        var value = total
        val required = properties.toMutableList().apply { sortByDescending { it.assets } }
        val optional = arrayListOf<ZoneProperty>()

        required.removeIf { zone ->
            val zoneAssets = zone.assets
            if (requestAmount < value - zoneAssets) {
                value -= zoneAssets
                optional.add(zone)
                true
            } else false
        }

        return required to optional
    }

    private suspend fun withdraw(requestAmount: Int): Int {
        if (balance < requestAmount) {
            val assets = assets
            val total = balance + assets

            if (total < requestAmount) {
                isBankrupt = true

                for (property in properties) {
                    property.clear()
                }

                balance = total
            } else {
                selectProperties(total, requestAmount).let { (required, optional) ->
                    if (optional.isEmpty()) {
                        for (property in properties) property.clear()
                        balance = total
                    } else {
                        val selected = request(GameDialogSeizure(), GameMessage.SEIZURE) { required }.filter { it.owner == this }

                        for (zone in selected) {
                            balance += zone.assets
                            zone.clear()
                        }

                        if (balance < requestAmount) {
                            for (zone in selectProperties(total, requestAmount).first) {
                                balance += zone.assets
                                zone.clear()
                            }
                        }
                    }
                }
            }
        }

        return min(balance, requestAmount).also { balance -= it }
    }

    internal suspend fun withdraw(requestAmount: Int, source: Any) {
        val amount = withdraw(requestAmount)

        board.game.eventAdapter.call(PieceWithdrawEvent(this, amount, source))
        ensureAlive()
    }

    internal suspend fun transfer(requestAmount: Int, receiver: Piece, source: Any) {
        val amount = withdraw(requestAmount)
        receiver.balance += amount

        board.game.eventAdapter.call(PieceTransferEvent(this, amount, receiver, source))
        ensureAlive()
    }

    internal fun ensureAlive() {
        if (isBankrupt) throw BankruptException(this)
    }


}