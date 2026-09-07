import 'dart:async';
import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../models/market_models.dart';
import '../models/trade_history.dart';
import '../screens/api_settings_screen.dart';
import '../services/bot_engine_service.dart';

/// КАРТОЧКА УПРАВЛЕНИЯ АВТО-БОТОМ (HUD СТИЛЬ)
/// 
/// Включает:
/// - Большая кнопка "▶ ЗАПУСТИТЬ БОТА" / "⏹ ОСТАНОВИТЬ БОТА" с неоновой пульсирующей анимацией
/// - Диалог подтверждения перед запуском с предупреждением о риске
/// - Индикатор аптайма (HH:mm:ss)
/// - Живой терминал логов с автоскроллом к последней записи
/// - Кнопка "Экстренно закрыть все позиции"
class BotControlCard extends StatefulWidget {
  final TradingPair? selectedPair;
  final double usdtPositionAmount;
  final StrategyRiskConfig strategyConfig;
  final VoidCallback onRefreshNeeded;

  const BotControlCard({
    Key? key,
    this.selectedPair,
    required this.usdtPositionAmount,
    required this.strategyConfig,
    required this.onRefreshNeeded,
  }) : super(key: key);

  @override
  State<BotControlCard> createState() => _BotControlCardState();
}

class _BotControlCardState extends State<BotControlCard>
    with SingleTickerProviderStateMixin {
  final BotEngineService _engine = BotEngineService();
  late AnimationController _pulseController;
  late Animation<double> _pulseAnimation;

  final ScrollController _logScrollController = ScrollController();
  StreamSubscription<EngineStatus>? _statusSub;
  StreamSubscription<String>? _logSub;
  Timer? _uptimeTimer;

  Duration _currentUptime = Duration.zero;

  @override
  void initState() {
    super.initState();
    // Контроллер неоновой пульсации кнопки при активном боте
    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1400),
    )..repeat(reverse: true);

    _pulseAnimation = Tween<double>(begin: 0.85, end: 1.0).animate(
      CurvedAnimation(parent: _pulseController, curve: Curves.easeInOut),
    );

    _statusSub = _engine.statusStream.listen((status) {
      if (mounted) setState(() {});
      widget.onRefreshNeeded();
    });

    _logSub = _engine.logStream.listen((_) {
      if (mounted) {
        setState(() {});
        _scrollToBottom();
      }
    });

    // Таймер обновления аптайма
    _uptimeTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (_engine.status == EngineStatus.running && mounted) {
        setState(() {
          _currentUptime = _engine.uptime;
        });
      }
    });
  }

  @override
  void dispose() {
    _pulseController.dispose();
    _logScrollController.dispose();
    _statusSub?.cancel();
    _logSub?.cancel();
    _uptimeTimer?.cancel();
    super.dispose();
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_logScrollController.hasClients) {
        _logScrollController.animateTo(
          _logScrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 200),
          curve: Curves.easeOut,
        );
      }
    });
  }

  String _formatDuration(Duration d) {
    final hours = d.inHours.toString().padLeft(2, '0');
    final minutes = (d.inMinutes % 60).toString().padLeft(2, '0');
    final seconds = (d.inSeconds % 60).toString().padLeft(2, '0');
    return '$hours:$minutes:$seconds';
  }

  /// Диалог подтверждения старта авто-торговли
  Future<void> _showStartConfirmDialog() async {
    final pair = widget.selectedPair?.symbol ?? 'BTCUSDT';
    final amount = widget.usdtPositionAmount;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => Dialog(
        backgroundColor: Colors.transparent,
        child: HudCard(
          borderColor: const Color(0xFF00D4FF),
          glowColor: const Color(0x3300D4FF),
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Icon(Icons.rocket_launch_outlined, color: Color(0xFF00D4FF), size: 24),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        'ЗАПУСК АВТО-ТОРГОВЛИ',
                        style: GoogleFonts.orbitron(
                          color: Colors.white,
                          fontSize: 14,
                          fontWeight: FontWeight.bold,
                          letterSpacing: 1.1,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 14),
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: const Color(0xFF071220),
                    borderRadius: BorderRadius.circular(6),
                    border: Border.all(color: const Color(0x3300D4FF)),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'ПАРАМЕТРЫ СЕССИИ:',
                        style: GoogleFonts.orbitron(color: const Color(0xFF7E9BB8), fontSize: 10),
                      ),
                      const SizedBox(height: 6),
                      Text('• Инструмент: $pair', style: GoogleFonts.rajdhani(color: Colors.white, fontSize: 13, fontWeight: FontWeight.w600)),
                      Text('• Размер ордера: $amount USDT', style: GoogleFonts.rajdhani(color: const Color(0xFFFF8A65), fontSize: 13, fontWeight: FontWeight.bold)),
                      Text('• Порог Score входа: ≥ ${widget.strategyConfig.minScoreThreshold}', style: GoogleFonts.rajdhani(color: const Color(0xFF00D4FF), fontSize: 13)),
                      Text('• Авто Stop-Loss: ${widget.strategyConfig.stopLossPercent}%', style: GoogleFonts.rajdhani(color: const Color(0xFFFF5252), fontSize: 13)),
                      Text('• Авто Take-Profit: ${widget.strategyConfig.takeProfitPercent}%', style: GoogleFonts.rajdhani(color: const Color(0xFF00E676), fontSize: 13)),
                    ],
                  ),
                ),
                const SizedBox(height: 14),
                Text(
                  'ВНИМАНИЕ: Бот начнёт автоматически открывать и закрывать сделки на $pair суммой $amount USDT без дополнительного подтверждения. Продолжить?',
                  style: GoogleFonts.rajdhani(color: const Color(0xFFB0C4DE), fontSize: 13),
                ),
                const SizedBox(height: 20),
                Row(
                  children: [
                    Expanded(
                      child: HudButton(
                        text: 'ОТМЕНА',
                        isOutlined: true,
                        color: const Color(0xFF7E9BB8),
                        onPressed: () => Navigator.pop(ctx, false),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: HudButton(
                        text: 'СТАРТ',
                        icon: Icons.play_arrow_rounded,
                        color: const Color(0xFF00D4FF),
                        onPressed: () => Navigator.pop(ctx, true),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );

    if (confirmed == true) {
      await _engine.start(
        symbol: pair,
        positionUsdtAmount: amount,
        pairInfo: widget.selectedPair,
        strategyConfig: widget.strategyConfig,
      );
    }
  }

  /// Экстренная остановка с закрытием всех позиций
  Future<void> _showEmergencyDialog() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => Dialog(
        backgroundColor: Colors.transparent,
        child: HudCard(
          borderColor: const Color(0xFFFF1744),
          glowColor: const Color(0x33FF1744),
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Icon(Icons.warning_rounded, color: Color(0xFFFF1744), size: 24),
                    const SizedBox(width: 8),
                    Text(
                      'ЭКСТРЕННАЯ ОСТАНОВКА',
                      style: GoogleFonts.orbitron(
                        color: Colors.white,
                        fontSize: 14,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Text(
                  'Немедленно остановить цикл бота и закрыть по рынку все открытые позиции на Binance Testnet?',
                  style: GoogleFonts.rajdhani(color: const Color(0xFFB0C4DE), fontSize: 13),
                ),
                const SizedBox(height: 20),
                Row(
                  children: [
                    Expanded(
                      child: HudButton(
                        text: 'ОТМЕНА',
                        isOutlined: true,
                        color: const Color(0xFF7E9BB8),
                        onPressed: () => Navigator.pop(ctx, false),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: HudButton(
                        text: 'ЗАКРЫТЬ ВСЁ',
                        icon: Icons.power_settings_new,
                        color: const Color(0xFFFF1744),
                        onPressed: () => Navigator.pop(ctx, true),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );

    if (confirmed == true) {
      await _engine.stop();
      await _engine.emergencyCloseAll();
      widget.onRefreshNeeded();
    }
  }

  @override
  Widget build(BuildContext context) {
    final status = _engine.status;
    final isRunning = status == EngineStatus.running;
    final statusColor = Color(status.colorValue);

    return HudCard(
      borderColor: isRunning ? const Color(0xFF00D4FF) : const Color(0x66FF5252),
      glowColor: isRunning ? const Color(0x3300D4FF) : const Color(0x1AFF5252),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Заголовок карточки
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Row(
                  children: [
                    const Icon(Icons.smart_toy_outlined, color: Color(0xFF00D4FF), size: 18),
                    const SizedBox(width: 8),
                    Text(
                      'ЯДРО АВТО-ТОРГОВЛИ',
                      style: GoogleFonts.orbitron(
                        color: const Color(0xFF00D4FF),
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                        letterSpacing: 1.1,
                      ),
                    ),
                  ],
                ),
                // Индикатор аптайма
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: const Color(0xFF071220),
                    borderRadius: BorderRadius.circular(4),
                    border: Border.all(color: const Color(0x3300D4FF)),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.timer_outlined, size: 12, color: Color(0xFF7E9BB8)),
                      const SizedBox(width: 4),
                      Text(
                        'UPTIME: ${_formatDuration(_currentUptime)}',
                        style: GoogleFonts.orbitron(
                          color: isRunning ? const Color(0xFF00E676) : const Color(0xFF7E9BB8),
                          fontSize: 10,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),

            // БОЛЬШАЯ КНОПКА ЗАПУСКА / ОСТАНОВКИ БОТА С НЕОНОВОЙ ПУЛЬСАЦИЕЙ
            AnimatedBuilder(
              animation: _pulseAnimation,
              builder: (context, child) {
                final scale = isRunning ? _pulseAnimation.value : 1.0;
                return Transform.scale(
                  scale: scale,
                  child: child,
                );
              },
              child: Material(
                color: Colors.transparent,
                child: InkWell(
                  onTap: () {
                    if (isRunning) {
                      _engine.stop();
                    } else {
                      _showStartConfirmDialog();
                    }
                  },
                  borderRadius: BorderRadius.circular(10),
                  child: Ink(
                    padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 20),
                    decoration: BoxDecoration(
                      color: isRunning ? const Color(0x2A00E676) : const Color(0x1F00D4FF),
                      borderRadius: BorderRadius.circular(10),
                      border: Border.all(
                        color: isRunning ? const Color(0xFF00E676) : const Color(0xFF00D4FF),
                        width: 2.2,
                      ),
                      boxShadow: [
                        BoxShadow(
                          color: (isRunning ? const Color(0xFF00E676) : const Color(0xFF00D4FF))
                              .withOpacity(isRunning ? 0.45 : 0.25),
                          blurRadius: isRunning ? 18 : 12,
                          spreadRadius: isRunning ? 2 : 0,
                        ),
                      ],
                    ),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(
                          isRunning ? Icons.stop_circle_rounded : Icons.play_circle_fill_rounded,
                          color: isRunning ? const Color(0xFFFF5252) : const Color(0xFF00D4FF),
                          size: 32,
                        ),
                        const SizedBox(width: 14),
                        Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              isRunning ? '⏹ ОСТАНОВИТЬ БОТА' : '▶ ЗАПУСТИТЬ БОТА',
                              style: GoogleFonts.orbitron(
                                color: Colors.white,
                                fontSize: 15,
                                fontWeight: FontWeight.bold,
                                letterSpacing: 1.4,
                              ),
                            ),
                            Text(
                              isRunning
                                  ? 'Нажмите для мгновенной паузы цикла'
                                  : 'Непрерывный анализ и авто-ордера Spot',
                              style: GoogleFonts.rajdhani(
                                color: const Color(0xFFB0C4DE),
                                fontSize: 12,
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
            const SizedBox(height: 14),

            // Индикатор текущего статуса движка
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              decoration: BoxDecoration(
                color: const Color(0xFF071220),
                borderRadius: BorderRadius.circular(6),
                border: Border.all(color: statusColor.withOpacity(0.5)),
              ),
              child: Row(
                children: [
                  Container(
                    width: 10,
                    height: 10,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: statusColor,
                      boxShadow: [
                        BoxShadow(color: statusColor.withOpacity(0.8), blurRadius: 6),
                      ],
                    ),
                  ),
                  const SizedBox(width: 10),
                  Text(
                    'СТАТУС: ',
                    style: GoogleFonts.orbitron(
                      color: const Color(0xFF7E9BB8),
                      fontSize: 10,
                      letterSpacing: 0.8,
                    ),
                  ),
                  Expanded(
                    child: Text(
                      status.title,
                      style: GoogleFonts.orbitron(
                        color: statusColor,
                        fontSize: 11,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 14),

            // ЖИВОЙ ЛОГ ДЕЙСТВИЙ БОТА (АВТОСКРОЛЛ)
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Row(
                  children: [
                    const Icon(Icons.terminal_rounded, size: 14, color: Color(0xFF00D4FF)),
                    const SizedBox(width: 6),
                    Text(
                      'ЖИВОЙ ЛОГ ДЕЙСТВИЙ // АВТОСКРОЛЛ',
                      style: GoogleFonts.orbitron(
                        color: const Color(0xFF7E9BB8),
                        fontSize: 10,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ],
                ),
                Text(
                  '${_engine.recentLogs.length} событий',
                  style: GoogleFonts.rajdhani(color: const Color(0xFF536D88), fontSize: 10),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Container(
              height: 130,
              width: double.infinity,
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: const Color(0xFF060E18),
                borderRadius: BorderRadius.circular(6),
                border: Border.all(color: const Color(0x3300D4FF)),
              ),
              child: _engine.recentLogs.isEmpty
                  ? Center(
                      child: Text(
                        'Ожидание запуска бота...\nЛоги анализа, сигналов и авто-ордеров будут выводиться здесь.',
                        textAlign: TextAlign.center,
                        style: GoogleFonts.rajdhani(
                          color: const Color(0xFF536D88),
                          fontSize: 11,
                        ),
                      ),
                    )
                  : ListView.builder(
                      controller: _logScrollController,
                      itemCount: _engine.recentLogs.length,
                      itemBuilder: (context, index) {
                        final log = _engine.recentLogs[index];
                        Color logColor = const Color(0xFFB0C4DE);
                        if (log.contains('ОШИБКА') || log.contains('STOP-LOSS')) {
                          logColor = const Color(0xFFFF5252);
                        } else if (log.contains('TAKE-PROFIT') || log.contains('СДЕЛКА ИСПОЛНЕНА') || log.contains('УСПЕШНО')) {
                          logColor = const Color(0xFF00E676);
                        } else if (log.contains('СИГНАЛ LONG') || log.contains('АВТО-ВХОД')) {
                          logColor = const Color(0xFFFF8A65);
                        } else if (log.contains('Анализ') || log.contains('Цена:')) {
                          logColor = const Color(0xFF00D4FF);
                        }

                        return Padding(
                          padding: const EdgeInsets.symmetric(vertical: 1.5),
                          child: Text(
                            log,
                            style: GoogleFonts.orbitron(
                              color: logColor,
                              fontSize: 9.5,
                              letterSpacing: 0.3,
                            ),
                          ),
                        );
                      },
                    ),
            ),
            const SizedBox(height: 14),

            // Кнопка "Экстренно закрыть все позиции"
            HudButton(
              text: 'ЭКСТРЕННО ЗАКРЫТЬ ВСЕ ПОЗИЦИИ',
              icon: Icons.warning_amber_rounded,
              color: const Color(0xFFFF1744),
              isOutlined: false,
              onPressed: _showEmergencyDialog,
            ),
          ],
        ),
      ),
    );
  }
}
