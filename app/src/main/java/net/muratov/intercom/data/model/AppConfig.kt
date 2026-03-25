package net.muratov.intercom.data.model

import net.muratov.intercom.provider.myhome.MyHomeProptechConfig

data class AppConfig(
    val webViewUrl: String = "about:blank",
    val streams: List<StreamSourceConfig> = emptyList(),
    val sipAccounts: List<SipAccountSourceConfig> = emptyList(),
    val myHomeProptech: MyHomeProptechConfig = MyHomeProptechConfig(),
)
