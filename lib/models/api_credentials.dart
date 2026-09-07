import 'dart:convert';

/// Модель для хранения учетных данных API Binance
class ApiCredentials {
  final String profileName;
  final String apiKey;
  final String secretKey;
  final bool isTestnet;
  final DateTime updatedAt;

  const ApiCredentials({
    required this.profileName,
    required this.apiKey,
    required this.secretKey,
    this.isTestnet = true,
    required this.updatedAt,
  });

  Map<String, dynamic> toJson() => {
    'profileName': profileName,
    'apiKey': apiKey,
    'secretKey': secretKey,
    'isTestnet': isTestnet,
    'updatedAt': updatedAt.toIso8601String(),
  };

  factory ApiCredentials.fromJson(Map<String, dynamic> json) {
    return ApiCredentials(
      profileName: json['profileName'] as String? ?? 'Default Profile',
      apiKey: json['apiKey'] as String? ?? '',
      secretKey: json['secretKey'] as String? ?? '',
      isTestnet: json['isTestnet'] as bool? ?? true,
      updatedAt: json['updatedAt'] != null 
          ? DateTime.tryParse(json['updatedAt'] as String) ?? DateTime.now()
          : DateTime.now(),
    );
  }

  ApiCredentials copyWith({
    String? profileName,
    String? apiKey,
    String? secretKey,
    bool? isTestnet,
    DateTime? updatedAt,
  }) {
    return ApiCredentials(
      profileName: profileName ?? this.profileName,
      apiKey: apiKey ?? this.apiKey,
      secretKey: secretKey ?? this.secretKey,
      isTestnet: isTestnet ?? this.isTestnet,
      updatedAt: updatedAt ?? this.updatedAt,
    );
  }

  bool get isValid => apiKey.trim().isNotEmpty && secretKey.trim().isNotEmpty;
}

/// Баланс отдельного актива из ответа Binance /api/v3/account
class AssetBalance {
  final String asset;
  final double free;
  final double locked;

  const AssetBalance({
    required this.asset,
    required this.free,
    required this.locked,
  });

  double get total => free + locked;
  bool get isNonZero => total > 0.00000001;

  factory AssetBalance.fromJson(Map<String, dynamic> json) {
    return AssetBalance(
      asset: json['asset'] as String? ?? '',
      free: double.tryParse(json['free']?.toString() ?? '0') ?? 0.0,
      locked: double.tryParse(json['locked']?.toString() ?? '0') ?? 0.0,
    );
  }

  Map<String, dynamic> toJson() => {
    'asset': asset,
    'free': free,
    'locked': locked,
  };
}

/// Информация об аккаунте Binance (/api/v3/account)
class BinanceAccountInfo {
  final int makerCommission;
  final int takerCommission;
  final int buyerCommission;
  final int sellerCommission;
  final bool canTrade;
  final bool canWithdraw;
  final bool canDeposit;
  final int updateTime;
  final String accountType;
  final List<AssetBalance> balances;
  final List<String> permissions;

  const BinanceAccountInfo({
    required this.makerCommission,
    required this.takerCommission,
    required this.buyerCommission,
    required this.sellerCommission,
    required this.canTrade,
    required this.canWithdraw,
    required this.canDeposit,
    required this.updateTime,
    required this.accountType,
    required this.balances,
    required this.permissions,
  });

  /// Отфильтрованный список балансов только с ненулевым объемом
  List<AssetBalance> get nonZeroBalances =>
      balances.where((b) => b.isNonZero).toList()
        ..sort((a, b) => b.total.compareTo(a.total));

  DateTime get lastUpdatedDateTime =>
      DateTime.fromMillisecondsSinceEpoch(updateTime);

  factory BinanceAccountInfo.fromJson(Map<String, dynamic> json) {
    final rawBalances = json['balances'] as List<dynamic>? ?? [];
    final rawPermissions = json['permissions'] as List<dynamic>? ?? [];

    return BinanceAccountInfo(
      makerCommission: json['makerCommission'] as int? ?? 0,
      takerCommission: json['takerCommission'] as int? ?? 0,
      buyerCommission: json['buyerCommission'] as int? ?? 0,
      sellerCommission: json['sellerCommission'] as int? ?? 0,
      canTrade: json['canTrade'] as bool? ?? false,
      canWithdraw: json['canWithdraw'] as bool? ?? false,
      canDeposit: json['canDeposit'] as bool? ?? false,
      updateTime: json['updateTime'] as int? ?? DateTime.now().millisecondsSinceEpoch,
      accountType: json['accountType'] as String? ?? 'SPOT',
      balances: rawBalances
          .map((b) => AssetBalance.fromJson(b as Map<String, dynamic>))
          .toList(),
      permissions: rawPermissions.map((p) => p.toString()).toList(),
    );
  }
}

/// Результат проверки подключения к Binance
class ConnectionCheckResult {
  final bool isConnected;
  final bool isPingOk;
  final BinanceAccountInfo? accountInfo;
  final String? errorMessage;
  final DateTime checkTimestamp;

  const ConnectionCheckResult({
    required this.isConnected,
    required this.isPingOk,
    this.accountInfo,
    this.errorMessage,
    required this.checkTimestamp,
  });

  factory ConnectionCheckResult.success({
    required BinanceAccountInfo accountInfo,
  }) {
    return ConnectionCheckResult(
      isConnected: true,
      isPingOk: true,
      accountInfo: accountInfo,
      errorMessage: null,
      checkTimestamp: DateTime.now(),
    );
  }

  factory ConnectionCheckResult.error({
    required String message,
    bool isPingOk = false,
  }) {
    return ConnectionCheckResult(
      isConnected: false,
      isPingOk: isPingOk,
      accountInfo: null,
      errorMessage: message,
      checkTimestamp: DateTime.now(),
    );
  }
}
