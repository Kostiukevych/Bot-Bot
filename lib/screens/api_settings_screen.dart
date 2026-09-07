import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
import '../models/api_credentials.dart';
import '../services/binance_auth_service.dart';
import '../services/secure_storage_service.dart';

/// Футуристичный экран настройки API Binance Spot Testnet в стиле Cyberpunk HUD
class ApiSettingsScreen extends StatefulWidget {
  const ApiSettingsScreen({Key? key}) : super(key: key);

  @override
  State<ApiSettingsScreen> createState() => _ApiSettingsScreenState();
}

class _ApiSettingsScreenState extends State<ApiSettingsScreen> {
  final _formKey = GlobalKey<FormState>();

  final TextEditingController _profileNameController =
      TextEditingController(text: 'Spot Testnet Master');
  final TextEditingController _apiKeyController = TextEditingController();
  final TextEditingController _secretKeyController = TextEditingController();

  final SecureStorageService _storageService = SecureStorageService();
  final BinanceAuthService _authService = BinanceAuthService();

  bool _obscureApiKey = true;
  bool _obscureSecretKey = true;
  bool _isTestnet = true; // По умолчанию Testnet
  bool _isLoading = false;
  String _loadingMessage = '';

  ConnectionCheckResult? _checkResult;
  BinanceAccountInfo? _accountInfo;
  DateTime? _lastFetchTime;

  @override
  void initState() {
    super.initState();
    _loadSavedCredentials();
  }

  @override
  void dispose() {
    _profileNameController.dispose();
    _apiKeyController.dispose();
    _secretKeyController.dispose();
    super.dispose();
  }

  /// Загрузка существующих учетных данных из Keystore
  Future<void> _loadSavedCredentials() async {
    setState(() => _isLoading = true);
    try {
      final creds = await _storageService.getCredentials();
      if (creds != null) {
        _profileNameController.text = creds.profileName;
        _apiKeyController.text = creds.apiKey;
        _secretKeyController.text = creds.secretKey;
        _isTestnet = creds.isTestnet;
      }
    } catch (e) {
      _showSnackbar('Ошибка загрузки ключей: $e', isError: true);
    } finally {
      setState(() => _isLoading = false);
    }
  }

  /// Проверка подключения (Ping + /api/v3/account)
  Future<void> _checkConnection() async {
    final apiKey = _apiKeyController.text.trim();
    final secretKey = _secretKeyController.text.trim();

    if (apiKey.isEmpty || secretKey.isEmpty) {
      _showSnackbar('Заполните API Key и Secret Key перед проверкой', isError: true);
      return;
    }

    setState(() {
      _isLoading = true;
      _loadingMessage = 'CYBER-PING & SIGNATURE VERIFICATION...';
    });

    try {
      final result = await _authService.checkConnection(
        apiKey: apiKey,
        secretKey: secretKey,
        isTestnet: _isTestnet,
      );

      setState(() {
        _checkResult = result;
        if (result.isConnected && result.accountInfo != null) {
          _accountInfo = result.accountInfo;
          _lastFetchTime = DateTime.now();
        }
      });

      if (result.isConnected) {
        _showSnackbar('✅ Успешное подключение к Binance Testnet!');
      } else {
        _showSnackbar('❌ Ошибка: ${result.errorMessage}', isError: true);
      }
    } catch (e) {
      final err = e.toString().replaceFirst('Exception: ', '');
      setState(() {
        _checkResult = ConnectionCheckResult.error(message: err);
      });
      _showSnackbar('❌ Ошибка: $err', isError: true);
    } finally {
      setState(() {
        _isLoading = false;
        _loadingMessage = '';
      });
    }
  }

  /// Сохранение ключей с предварительной валидацией через /api/v3/account
  Future<void> _saveKeys() async {
    if (!_formKey.currentState!.validate()) return;

    final credentials = ApiCredentials(
      profileName: _profileNameController.text.trim().isEmpty
          ? 'Spot Testnet'
          : _profileNameController.text.trim(),
      apiKey: _apiKeyController.text.trim(),
      secretKey: _secretKeyController.text.trim(),
      isTestnet: _isTestnet,
      updatedAt: DateTime.now(),
    );

    setState(() {
      _isLoading = true;
      _loadingMessage = 'ENCRYPTING & VERIFYING SIGNATURE...';
    });

    try {
      final account = await _authService.validateAndSave(credentials);
      setState(() {
        _accountInfo = account;
        _lastFetchTime = DateTime.now();
        _checkResult = ConnectionCheckResult.success(accountInfo: account);
      });
      _showSnackbar('Ключи успешно проверены и сохранены в Android Keystore!');
    } catch (e) {
      final err = e.toString().replaceFirst('Exception: ', '');
      _showSnackbar('Ошибка валидации ключей: $err', isError: true);
    } finally {
      setState(() {
        _isLoading = false;
        _loadingMessage = '';
      });
    }
  }

  /// Диалог подтверждения сброса ключей
  void _confirmResetKeys() {
    _showHudDialog(
      title: 'СБРОС КЛЮЧЕЙ ПРОФИЛЯ',
      content: 'Вы уверены, что хотите удалить сохраненные API и Secret ключи для текущего профиля из защищенного хранилища?',
      confirmText: 'УДАЛИТЬ КЛЮЧИ',
      confirmColor: const Color(0xFFFF5252),
      onConfirm: () async {
        Navigator.pop(context);
        setState(() => _isLoading = true);
        try {
          await _storageService.deleteCredentials();
          _apiKeyController.clear();
          _secretKeyController.clear();
          setState(() {
            _checkResult = null;
            _accountInfo = null;
            _lastFetchTime = null;
          });
          _showSnackbar('API-ключи текущего профиля успешно удалены');
        } catch (e) {
          _showSnackbar('Ошибка удаления: $e', isError: true);
        } finally {
          setState(() => _isLoading = false);
        }
      },
    );
  }

  /// Диалог полного сброса всего приложения
  void _confirmResetAll() {
    _showHudDialog(
      title: 'ПОЛНЫЙ СБРОС СИСТЕМЫ (PURGE)',
      content: 'ВНИМАНИЕ! Это действие сотрет ВСЕ профили API, сохраненные настройки торговой стратегии и локальную историю сделок. Действие необратимо.',
      confirmText: 'СБРОСИТЬ ВСЁ',
      confirmColor: const Color(0xFFFF1744),
      onConfirm: () async {
        Navigator.pop(context);
        setState(() => _isLoading = true);
        try {
          await _storageService.clearAllData();
          _profileNameController.text = 'Spot Testnet Master';
          _apiKeyController.clear();
          _secretKeyController.clear();
          _isTestnet = true;
          setState(() {
            _checkResult = null;
            _accountInfo = null;
            _lastFetchTime = null;
          });
          _showSnackbar('Система полностью очищена: ключи, настройки, история стёрты');
        } catch (e) {
          _showSnackbar('Ошибка сброса: $e', isError: true);
        } finally {
          setState(() => _isLoading = false);
        }
      },
    );
  }

  void _showSnackbar(String text, {bool isError = false}) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        backgroundColor: isError ? const Color(0xFF381015) : const Color(0xFF072733),
        shape: RoundedRectangleBorder(
          side: BorderSide(
            color: isError ? const Color(0xFFFF5252) : const Color(0xFF00D4FF),
            width: 1.5,
          ),
          borderRadius: BorderRadius.circular(6),
        ),
        behavior: SnackBarBehavior.floating,
        content: Row(
          children: [
            Icon(
              isError ? Icons.warning_amber_rounded : Icons.check_circle_outline,
              color: isError ? const Color(0xFFFF5252) : const Color(0xFF00D4FF),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                text,
                style: GoogleFonts.rajdhani(
                  color: Colors.white,
                  fontWeight: FontWeight.w600,
                  fontSize: 14,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _showHudDialog({
    required String title,
    required String content,
    required String confirmText,
    required Color confirmColor,
    required VoidCallback onConfirm,
  }) {
    showDialog(
      context: context,
      builder: (ctx) => Dialog(
        backgroundColor: Colors.transparent,
        child: HudCard(
          borderColor: confirmColor,
          glowColor: confirmColor.withOpacity(0.3),
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(Icons.shield_outlined, color: confirmColor, size: 24),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        title,
                        style: GoogleFonts.orbitron(
                          color: Colors.white,
                          fontSize: 15,
                          fontWeight: FontWeight.bold,
                          letterSpacing: 1.1,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 14),
                Text(
                  content,
                  style: GoogleFonts.rajdhani(
                    color: const Color(0xFFB0C4DE),
                    fontSize: 14,
                    height: 1.4,
                  ),
                ),
                const SizedBox(height: 24),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    TextButton(
                      onPressed: () => Navigator.pop(ctx),
                      child: Text(
                        'ОТМЕНА',
                        style: GoogleFonts.orbitron(
                          color: const Color(0xFF7E9BB8),
                          fontSize: 12,
                        ),
                      ),
                    ),
                    const SizedBox(width: 12),
                    HudButton(
                      text: confirmText,
                      color: confirmColor,
                      onPressed: onConfirm,
                      isSmall: true,
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0A1628),
      body: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              Color(0xFF0A1628),
              Color(0xFF0D2340),
              Color(0xFF081220),
            ],
          ),
        ),
        child: SafeArea(
          child: Stack(
            children: [
              // Фоновая кибернетическая сетка
              const Positioned.fill(
                child: CustomPaint(painter: HudGridBackgroundPainter()),
              ),

              Column(
                children: [
                  _buildHeader(),
                  Expanded(
                    child: SingleChildScrollView(
                      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                      child: Form(
                        key: _formKey,
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            _buildProfileCard(),
                            const SizedBox(height: 14),
                            _buildNetworkSelectorCard(),
                            const SizedBox(height: 14),
                            _buildApiCredentialsCard(),
                            const SizedBox(height: 18),
                            _buildActionButtons(),
                            const SizedBox(height: 18),
                            _buildConnectionStatusBanner(),
                            if (_accountInfo != null) ...[
                              const SizedBox(height: 18),
                              _buildAccountInfoHudCard(),
                            ],
                            const SizedBox(height: 18),
                            _buildDangerZone(),
                            const SizedBox(height: 32),
                          ],
                        ),
                      ),
                    ),
                  ),
                ],
              ),

              if (_isLoading) _buildLoadingOverlay(),
            ],
          ),
        ),
      ),
    );
  }

  /// Заголовок терминала HUD
  Widget _buildHeader() {
    return Container(
      padding: const EdgeInsets.fromLTRB(20, 14, 20, 14),
      decoration: BoxDecoration(
        color: const Color(0xFF0A1628).withOpacity(0.85),
        border: const Border(
          bottom: BorderSide(color: Color(0x3300D4FF), width: 1.5),
        ),
      ),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: const Color(0x1A00D4FF),
              border: Border.all(color: const Color(0xFF00D4FF), width: 1.2),
              borderRadius: BorderRadius.circular(8),
              boxShadow: [
                BoxShadow(
                  color: const Color(0xFF00D4FF).withOpacity(0.25),
                  blurRadius: 10,
                ),
              ],
            ),
            child: const Icon(Icons.hub_outlined, color: Color(0xFF00D4FF), size: 22),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'BINANCE TESTNET TRADER',
                  style: GoogleFonts.orbitron(
                    color: Colors.white,
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                    letterSpacing: 1.2,
                  ),
                ),
                const SizedBox(height: 2),
                Row(
                  children: [
                    Container(
                      width: 7,
                      height: 7,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: _checkResult?.isConnected == true
                            ? const Color(0xFF00E676)
                            : const Color(0xFFFF8A65),
                        boxShadow: [
                          BoxShadow(
                            color: (_checkResult?.isConnected == true
                                    ? const Color(0xFF00E676)
                                    : const Color(0xFFFF8A65))
                                .withOpacity(0.6),
                            blurRadius: 6,
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 6),
                    Text(
                      'SECURE HUD TERMINAL // SPOT API',
                      style: GoogleFonts.rajdhani(
                        color: const Color(0xFF7E9BB8),
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        letterSpacing: 1.0,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            decoration: BoxDecoration(
              color: const Color(0x1AFF8A65),
              border: Border.all(color: const Color(0xFFFF8A65), width: 1),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Text(
              'TESTNET',
              style: GoogleFonts.orbitron(
                color: const Color(0xFFFF8A65),
                fontSize: 10,
                fontWeight: FontWeight.bold,
                letterSpacing: 1,
              ),
            ),
          ),
        ],
      ),
    );
  }

  /// Карточка выбора профиля
  Widget _buildProfileCard() {
    return HudCard(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _buildSectionHeader(Icons.badge_outlined, 'ПРОФИЛЬ УЧЕТНЫХ ДАННЫХ'),
            const SizedBox(height: 12),
            _buildHudTextField(
              controller: _profileNameController,
              label: 'Название профиля',
              hint: 'Например: Spot Testnet Strategy #1',
              prefixIcon: Icons.account_circle_outlined,
              validator: (val) =>
                  val == null || val.trim().isEmpty ? 'Укажите название' : null,
            ),
          ],
        ),
      ),
    );
  }

  /// Карточка выбора сети (Testnet vs Live) с физической блокировкой
  Widget _buildNetworkSelectorCard() {
    return HudCard(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _buildSectionHeader(Icons.alt_route_rounded, 'РЕЖИМ ПОДКЛЮЧЕНИЯ (СЕТЬ)'),
            const SizedBox(height: 12),
            Row(
              children: [
                // Testnet кнопка
                Expanded(
                  child: Container(
                    padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 8),
                    decoration: BoxDecoration(
                      color: _isTestnet ? const Color(0x2600D4FF) : Colors.transparent,
                      border: Border.all(
                        color: _isTestnet ? const Color(0xFF00D4FF) : const Color(0x337E9BB8),
                        width: 1.5,
                      ),
                      borderRadius: BorderRadius.circular(8),
                      boxShadow: _isTestnet
                          ? [
                              BoxShadow(
                                color: const Color(0xFF00D4FF).withOpacity(0.2),
                                blurRadius: 8,
                              )
                            ]
                          : [],
                    ),
                    child: Column(
                      children: [
                        const Icon(Icons.science_outlined, color: Color(0xFF00D4FF), size: 22),
                        const SizedBox(height: 4),
                        Text(
                          'SPOT TESTNET',
                          style: GoogleFonts.orbitron(
                            color: Colors.white,
                            fontSize: 12,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        Text(
                          'testnet.binance.vision',
                          style: GoogleFonts.rajdhani(
                            color: const Color(0xFF7E9BB8),
                            fontSize: 10,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                // Live кнопка (заблокирована с подсказкой)
                Expanded(
                  child: Tooltip(
                    message: 'Доступно после подтверждения. Mainnet заблокирован для безопасности.',
                    child: Container(
                      padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 8),
                      decoration: BoxDecoration(
                        color: const Color(0x0DFFFFFF),
                        border: Border.all(color: const Color(0x22FFFFFF), width: 1),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Column(
                        children: [
                          const Icon(Icons.lock_outline, color: Color(0xFF7E9BB8), size: 22),
                          const SizedBox(height: 4),
                          Text(
                            'LIVE (MAINNET)',
                            style: GoogleFonts.orbitron(
                              color: const Color(0xFF7E9BB8),
                              fontSize: 12,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                          Text(
                            'Блокировка (только testnet)',
                            style: GoogleFonts.rajdhani(
                              color: const Color(0xFFFF8A65),
                              fontSize: 10,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              '⚠️ Защитный протокол: переключатель Live задизейблен для исключения риска реальных средств.',
              style: GoogleFonts.rajdhani(
                color: const Color(0xFF88A0BC),
                fontSize: 12,
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// Карточка ввода ключей
  Widget _buildApiCredentialsCard() {
    return HudCard(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _buildSectionHeader(Icons.vpn_key_outlined, 'API КЛЮЧИ (ANDROID KEYSTORE)'),
            const SizedBox(height: 14),

            // API Key
            _buildHudTextField(
              controller: _apiKeyController,
              label: 'API Key',
              hint: 'Вставьте публичный API ключ Testnet',
              prefixIcon: Icons.key_rounded,
              obscureText: _obscureApiKey,
              suffixIcon: IconButton(
                icon: Icon(
                  _obscureApiKey ? Icons.visibility_off : Icons.visibility,
                  color: const Color(0xFF00D4FF),
                  size: 20,
                ),
                onPressed: () => setState(() => _obscureApiKey = !_obscureApiKey),
              ),
              validator: (val) {
                if (val == null || val.trim().isEmpty) return 'Введите API Key';
                if (val.trim().length < 16) return 'Слишком короткий API Key';
                return null;
              },
            ),
            const SizedBox(height: 14),

            // Secret Key
            _buildHudTextField(
              controller: _secretKeyController,
              label: 'Secret Key',
              hint: 'Вставьте Secret Key для HMAC SHA256',
              prefixIcon: Icons.lock_clock_outlined,
              obscureText: _obscureSecretKey,
              suffixIcon: IconButton(
                icon: Icon(
                  _obscureSecretKey ? Icons.visibility_off : Icons.visibility,
                  color: const Color(0xFF00D4FF),
                  size: 20,
                ),
                onPressed: () => setState(() => _obscureSecretKey = !_obscureSecretKey),
              ),
              validator: (val) {
                if (val == null || val.trim().isEmpty) return 'Введите Secret Key';
                if (val.trim().length < 16) return 'Слишком короткий Secret Key';
                return null;
              },
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                const Icon(Icons.security, color: Color(0xFF00E676), size: 14),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    'Ключи шифруются аппаратно через flutter_secure_storage и никогда не передаются третьим лицам.',
                    style: GoogleFonts.rajdhani(
                      color: const Color(0xFF7E9BB8),
                      fontSize: 11,
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  /// Кнопки действий: Сохранить и Проверить
  Widget _buildActionButtons() {
    return Row(
      children: [
        Expanded(
          child: HudButton(
            text: 'ПРОВЕРИТЬ СВЯЗЬ',
            icon: Icons.radar_rounded,
            color: const Color(0xFF00D4FF),
            isOutlined: true,
            onPressed: _checkConnection,
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: HudButton(
            text: 'СОХРАНИТЬ КЛЮЧИ',
            icon: Icons.save_outlined,
            color: const Color(0xFF00D4FF),
            onPressed: _saveKeys,
          ),
        ),
      ],
    );
  }

  /// Баннер статуса последней проверки
  Widget _buildConnectionStatusBanner() {
    if (_checkResult == null) return const SizedBox.shrink();

    final isSuccess = _checkResult!.isConnected;
    final color = isSuccess ? const Color(0xFF00E676) : const Color(0xFFFF5252);

    return HudCard(
      borderColor: color,
      glowColor: color.withOpacity(0.25),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(
              isSuccess ? Icons.verified_outlined : Icons.error_outline,
              color: color,
              size: 26,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        isSuccess ? '✅ ПОДКЛЮЧЕНО К BINANCE' : '❌ ОШИБКА ПОДКЛЮЧЕНИЯ',
                        style: GoogleFonts.orbitron(
                          color: color,
                          fontSize: 13,
                          fontWeight: FontWeight.bold,
                          letterSpacing: 1,
                        ),
                      ),
                      Text(
                        DateFormat('HH:mm:ss').format(_checkResult!.checkTimestamp),
                        style: GoogleFonts.rajdhani(
                          color: const Color(0xFF7E9BB8),
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Text(
                    isSuccess
                      ? 'Сервер доступен (Ping OK). Права API и HMAC подпись успешно подтверждены.'
                      : (_checkResult!.errorMessage ?? 'Неизвестная ошибка'),
                    style: GoogleFonts.rajdhani(
                      color: isSuccess ? const Color(0xFFC8E6C9) : const Color(0xFFFFCDD2),
                      fontSize: 13,
                      height: 1.3,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// Карточка отображения информации об аккаунте после успешного подключения
  Widget _buildAccountInfoHudCard() {
    final info = _accountInfo!;
    final balances = info.nonZeroBalances;

    return HudCard(
      borderColor: const Color(0xFF00D4FF),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                _buildSectionHeader(Icons.account_balance_wallet_outlined, 'ДАННЫЕ АККАУНТА (HUD)'),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                  decoration: BoxDecoration(
                    color: const Color(0x2600D4FF),
                    borderRadius: BorderRadius.circular(4),
                    border: Border.all(color: const Color(0xFF00D4FF), width: 0.8),
                  ),
                  child: Text(
                    info.accountType,
                    style: GoogleFonts.orbitron(
                      color: const Color(0xFF00D4FF),
                      fontSize: 11,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 14),

            // Статусы разрешений
            Row(
              children: [
                _buildPermissionBadge('Trade', info.canTrade),
                const SizedBox(width: 8),
                _buildPermissionBadge('Withdraw', info.canWithdraw),
                const SizedBox(width: 8),
                _buildPermissionBadge('Deposit', info.canDeposit),
              ],
            ),
            const SizedBox(height: 16),

            // Комиссии
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              decoration: BoxDecoration(
                color: const Color(0x1A0A1628),
                borderRadius: BorderRadius.circular(6),
                border: Border.all(color: const Color(0x3300D4FF), width: 1),
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceAround,
                children: [
                  _buildMetricItem(
                    label: 'MAKER FEE',
                    value: '${(info.makerCommission / 100).toStringAsFixed(2)}%',
                  ),
                  Container(width: 1, height: 28, color: const Color(0x3300D4FF)),
                  _buildMetricItem(
                    label: 'TAKER FEE',
                    value: '${(info.takerCommission / 100).toStringAsFixed(2)}%',
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),

            // Список ненулевых балансов
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  'АКТИВНЫЕ БАЛАНСЫ (${balances.length})',
                  style: GoogleFonts.orbitron(
                    color: const Color(0xFFB0C4DE),
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                    letterSpacing: 1,
                  ),
                ),
                Text(
                  'FREE / LOCKED',
                  style: GoogleFonts.rajdhani(
                    color: const Color(0xFF7E9BB8),
                    fontSize: 11,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),

            if (balances.isEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 12),
                child: Center(
                  child: Text(
                    'Ненулевые балансы отсутствуют',
                    style: GoogleFonts.rajdhani(
                      color: const Color(0xFF7E9BB8),
                      fontSize: 13,
                    ),
                  ),
                ),
              )
            else
              ListView.separated(
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                itemCount: balances.length,
                separatorBuilder: (_, __) => const Divider(
                  color: Color(0x1A00D4FF),
                  height: 12,
                ),
                itemBuilder: (ctx, i) {
                  final b = balances[i];
                  return Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Row(
                        children: [
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                            decoration: BoxDecoration(
                              color: const Color(0x2600D4FF),
                              borderRadius: BorderRadius.circular(4),
                            ),
                            child: Text(
                              b.asset,
                              style: GoogleFonts.orbitron(
                                color: const Color(0xFF00D4FF),
                                fontSize: 12,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                          ),
                        ],
                      ),
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.end,
                        children: [
                          Text(
                            b.free.toStringAsFixed(b.free < 1 ? 6 : 4),
                            style: GoogleFonts.rajdhani(
                              color: const Color(0xFFFF8A65), // Персиковый акцент для чисел
                              fontSize: 14,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                          if (b.locked > 0)
                            Text(
                              'Locked: ${b.locked.toStringAsFixed(4)}',
                              style: GoogleFonts.rajdhani(
                                color: const Color(0xFF7E9BB8),
                                fontSize: 11,
                              ),
                            ),
                        ],
                      ),
                    ],
                  );
                },
              ),

            const SizedBox(height: 12),
            if (_lastFetchTime != null)
              Align(
                alignment: Alignment.centerRight,
                child: Text(
                  'Обновлено: ${DateFormat('yyyy-MM-dd HH:mm:ss').format(_lastFetchTime!)}',
                  style: GoogleFonts.rajdhani(
                    color: const Color(0xFF7E9BB8),
                    fontSize: 11,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  /// Элемент метрики (Комиссия и т.д.)
  Widget _buildMetricItem({required String label, required String value}) {
    return Column(
      children: [
        Text(
          label,
          style: GoogleFonts.orbitron(
            color: const Color(0xFF7E9BB8),
            fontSize: 10,
            letterSpacing: 0.8,
          ),
        ),
        const SizedBox(height: 2),
        Text(
          value,
          style: GoogleFonts.rajdhani(
            color: const Color(0xFFFF8A65), // Акцент для чисел
            fontSize: 16,
            fontWeight: FontWeight.bold,
          ),
        ),
      ],
    );
  }

  /// Бейдж прав аккаунта
  Widget _buildPermissionBadge(String name, bool isGranted) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 6),
        decoration: BoxDecoration(
          color: isGranted ? const Color(0x1A00E676) : const Color(0x1AFF5252),
          borderRadius: BorderRadius.circular(4),
          border: Border.all(
            color: isGranted ? const Color(0xFF00E676) : const Color(0xFFFF5252),
            width: 0.8,
          ),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              isGranted ? Icons.check : Icons.close,
              color: isGranted ? const Color(0xFF00E676) : const Color(0xFFFF5252),
              size: 14,
            ),
            const SizedBox(width: 4),
            Text(
              name,
              style: GoogleFonts.rajdhani(
                color: Colors.white,
                fontSize: 12,
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// Опасная зона: Сброс ключей и Сброс всего
  Widget _buildDangerZone() {
    return HudCard(
      borderColor: const Color(0x44FF5252),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _buildSectionHeader(
              Icons.warning_amber_rounded,
              'ОПЕРАЦИИ ОЧИСТКИ (DANGER ZONE)',
              color: const Color(0xFFFF5252),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: HudButton(
                    text: 'СБРОСИТЬ КЛЮЧИ',
                    icon: Icons.delete_outline,
                    color: const Color(0xFFFF5252),
                    isOutlined: true,
                    isSmall: true,
                    onPressed: _confirmResetKeys,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: HudButton(
                    text: 'СБРОСИТЬ ВСЁ',
                    icon: Icons.power_settings_new_rounded,
                    color: const Color(0xFFFF1744),
                    isSmall: true,
                    onPressed: _confirmResetAll,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSectionHeader(IconData icon, String title, {Color color = const Color(0xFF00D4FF)}) {
    return Row(
      children: [
        Icon(icon, color: color, size: 18),
        const SizedBox(width: 8),
        Text(
          title,
          style: GoogleFonts.orbitron(
            color: color,
            fontSize: 12,
            fontWeight: FontWeight.bold,
            letterSpacing: 1.1,
          ),
        ),
      ],
    );
  }

  Widget _buildHudTextField({
    required TextEditingController controller,
    required String label,
    required String hint,
    required IconData prefixIcon,
    bool obscureText = false,
    Widget? suffixIcon,
    String? Function(String?)? validator,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label.toUpperCase(),
          style: GoogleFonts.orbitron(
            color: const Color(0xFF7E9BB8),
            fontSize: 10,
            letterSpacing: 1,
            fontWeight: FontWeight.w600,
          ),
        ),
        const SizedBox(height: 6),
        TextFormField(
          controller: controller,
          obscureText: obscureText,
          style: GoogleFonts.shareTechMono(
            color: Colors.white,
            fontSize: 14,
          ),
          validator: validator,
          decoration: InputDecoration(
            hintText: hint,
            hintStyle: GoogleFonts.rajdhani(
              color: const Color(0xFF4A6582),
              fontSize: 13,
            ),
            filled: true,
            fillColor: const Color(0xFF071220),
            prefixIcon: Icon(prefixIcon, color: const Color(0xFF00D4FF), size: 18),
            suffixIcon: suffixIcon,
            contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
            enabledBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(6),
              borderSide: const BorderSide(color: Color(0x3300D4FF), width: 1),
            ),
            focusedBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(6),
              borderSide: const BorderSide(color: Color(0xFF00D4FF), width: 1.5),
            ),
            errorBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(6),
              borderSide: const BorderSide(color: Color(0xFFFF5252), width: 1),
            ),
            focusedErrorBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(6),
              borderSide: const BorderSide(color: Color(0xFFFF5252), width: 1.5),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildLoadingOverlay() {
    return Container(
      color: Colors.black.withOpacity(0.75),
      child: Center(
        child: HudCard(
          borderColor: const Color(0xFF00D4FF),
          glowColor: const Color(0xFF00D4FF).withOpacity(0.4),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const SizedBox(
                  width: 44,
                  height: 44,
                  child: CircularProgressIndicator(
                    strokeWidth: 3,
                    valueColor: AlwaysStoppedAnimation<Color>(Color(0xFF00D4FF)),
                  ),
                ),
                const SizedBox(height: 18),
                Text(
                  _loadingMessage.isNotEmpty ? _loadingMessage : 'PROCESSING...',
                  style: GoogleFonts.orbitron(
                    color: const Color(0xFF00D4FF),
                    fontSize: 12,
                    fontWeight: FontWeight.bold,
                    letterSpacing: 1.2,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// HUD-карточка со скошенными углами (Chamfered Corner) и неоновым свечением
class HudCard extends StatelessWidget {
  final Widget child;
  final Color borderColor;
  final Color glowColor;

  const HudCard({
    Key? key,
    required this.child,
    this.borderColor = const Color(0x4D00D4FF),
    this.glowColor = const Color(0x1A00D4FF),
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: const Color(0xFF0A182C).withOpacity(0.9),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: borderColor, width: 1.2),
        boxShadow: [
          BoxShadow(
            color: glowColor,
            blurRadius: 10,
            spreadRadius: 1,
          ),
        ],
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(9),
        child: Stack(
          children: [
            // Декоративный угловой срез HUD в правом верхнем углу
            Positioned(
              top: 0,
              right: 0,
              child: CustomPaint(
                size: const Size(20, 20),
                painter: HudCornerAccentPainter(color: borderColor),
              ),
            ),
            child,
          ],
        ),
      ),
    );
  }
}

/// Стильная неоновая кнопка терминала
class HudButton extends StatelessWidget {
  final String text;
  final VoidCallback onPressed;
  final IconData? icon;
  final Color color;
  final bool isOutlined;
  final bool isSmall;

  const HudButton({
    Key? key,
    required this.text,
    required this.onPressed,
    this.icon,
    this.color = const Color(0xFF00D4FF),
    this.isOutlined = false,
    this.isSmall = false,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onPressed,
        borderRadius: BorderRadius.circular(6),
        splashColor: color.withOpacity(0.3),
        child: Ink(
          padding: EdgeInsets.symmetric(
            vertical: isSmall ? 10 : 14,
            horizontal: isSmall ? 12 : 16,
          ),
          decoration: BoxDecoration(
            color: isOutlined ? color.withOpacity(0.1) : color,
            borderRadius: BorderRadius.circular(6),
            border: Border.all(color: color, width: 1.4),
            boxShadow: [
              BoxShadow(
                color: color.withOpacity(isOutlined ? 0.15 : 0.35),
                blurRadius: 8,
                spreadRadius: 0,
              ),
            ],
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              if (icon != null) ...[
                Icon(
                  icon,
                  size: isSmall ? 16 : 18,
                  color: isOutlined ? color : const Color(0xFF0A1628),
                ),
                const SizedBox(width: 8),
              ],
              Flexible(
                child: Text(
                  text,
                  textAlign: TextAlign.center,
                  style: GoogleFonts.orbitron(
                    color: isOutlined ? color : const Color(0xFF0A1628),
                    fontSize: isSmall ? 11 : 12,
                    fontWeight: FontWeight.bold,
                    letterSpacing: 1.1,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Художественная отрисовка углового HUD-акцента
class HudCornerAccentPainter extends CustomPainter {
  final Color color;
  const HudCornerAccentPainter({required this.color});

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color.withOpacity(0.7)
      ..style = PaintingStyle.fill;

    final path = Path()
      ..moveTo(size.width, 0)
      ..lineTo(0, 0)
      ..lineTo(size.width, size.height)
      ..close();

    canvas.drawPath(path, paint);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

/// Отрисовка кибернетической сетки на фоне
class HudGridBackgroundPainter extends CustomPainter {
  const HudGridBackgroundPainter();

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = const Color(0x0A00D4FF)
      ..strokeWidth = 1.0;

    const step = 36.0;
    for (double x = 0; x < size.width; x += step) {
      canvas.drawLine(Offset(x, 0), Offset(x, size.height), paint);
    }
    for (double y = 0; y < size.height; y += step) {
      canvas.drawLine(Offset(0, y), Offset(size.width, y), paint);
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
