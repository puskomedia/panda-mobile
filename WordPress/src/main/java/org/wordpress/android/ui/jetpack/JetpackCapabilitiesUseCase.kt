package org.wordpress.android.ui.jetpack

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.wordpress.android.fluxc.Dispatcher
import org.wordpress.android.fluxc.generated.SiteActionBuilder
import org.wordpress.android.fluxc.model.JetpackCapability
import org.wordpress.android.fluxc.model.JetpackCapability.BACKUP
import org.wordpress.android.fluxc.model.JetpackCapability.BACKUP_DAILY
import org.wordpress.android.fluxc.model.JetpackCapability.BACKUP_REALTIME
import org.wordpress.android.fluxc.model.JetpackCapability.SCAN
import org.wordpress.android.fluxc.store.SiteStore
import org.wordpress.android.fluxc.store.SiteStore.FetchJetpackCapabilitiesPayload
import org.wordpress.android.fluxc.store.SiteStore.OnJetpackCapabilitiesFetched
import org.wordpress.android.fluxc.utils.CurrentTimeProvider
import org.wordpress.android.modules.BG_THREAD
import org.wordpress.android.ui.prefs.AppPrefsWrapper
import javax.inject.Inject
import javax.inject.Named
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

const val MAX_CACHE_VALIDITY = 1000 * 60 * 15L // 15 minutes
private val SCAN_CAPABILITIES = listOf(SCAN)
val BACKUP_CAPABILITIES = listOf(BACKUP, BACKUP_DAILY, BACKUP_REALTIME)

class JetpackCapabilitiesUseCase @Inject constructor(
    @Suppress("unused") private val siteStore: SiteStore,
    private val dispatcher: Dispatcher,
    private val appPrefsWrapper: AppPrefsWrapper,
    private val currentDateProvider: CurrentTimeProvider,
    @param:Named(BG_THREAD) private val bgDispatcher: CoroutineDispatcher
) {
    private var continuation: Continuation<OnJetpackCapabilitiesFetched>? = null

    suspend fun getJetpackPurchasedProducts(remoteSiteId: Long) = flow {
        emit(getCachedJetpackPurchasedProducts(remoteSiteId))
        withContext(bgDispatcher) {
            if (!hasValidCache(remoteSiteId)) {
                emit(fetchJetpackPurchasedProducts(remoteSiteId))
            }
        }
    }

    fun getCachedJetpackPurchasedProducts(remoteSiteId: Long): JetpackPurchasedProducts =
            mapToJetpackPurchasedProducts(getCachedJetpackCapabilities(remoteSiteId))

    suspend fun fetchJetpackPurchasedProducts(remoteSiteId: Long): JetpackPurchasedProducts =
            mapToJetpackPurchasedProducts(fetchJetpackCapabilities(remoteSiteId))

    private fun mapToJetpackPurchasedProducts(capabilities: List<JetpackCapability>) =
            JetpackPurchasedProducts(
                    scan = capabilities.find { SCAN_CAPABILITIES.contains(it) } != null,
                    backup = capabilities.find { BACKUP_CAPABILITIES.contains(it) } != null
            )

    suspend fun hasValidCache(remoteSiteId: Long): Boolean {
        return withContext(bgDispatcher) {
            val lastUpdated = appPrefsWrapper.getSiteJetpackCapabilitiesLastUpdated(remoteSiteId)
            lastUpdated > currentDateProvider.currentDate().time - MAX_CACHE_VALIDITY
        }
    }

    private fun getCachedJetpackCapabilities(remoteSiteId: Long): List<JetpackCapability> {
        return appPrefsWrapper.getSiteJetpackCapabilities(remoteSiteId)
    }

    private suspend fun fetchJetpackCapabilities(remoteSiteId: Long): List<JetpackCapability> {
        return withContext(bgDispatcher) {
            if (continuation != null) {
                throw IllegalStateException("Request already in progress.")
            }

            dispatcher.register(this@JetpackCapabilitiesUseCase)
            val response = suspendCoroutine<OnJetpackCapabilitiesFetched> { cont ->
                val payload = FetchJetpackCapabilitiesPayload(remoteSiteId)
                continuation = cont
                dispatcher.dispatch(SiteActionBuilder.newFetchJetpackCapabilitiesAction(payload))
            }

            val capabilities: List<JetpackCapability> = response.capabilities ?: listOf()
            if (!response.isError) {
                updateCache(remoteSiteId, capabilities)
            }
            return@withContext capabilities
        }
    }

    private fun updateCache(
        remoteSiteId: Long,
        capabilities: List<JetpackCapability>
    ) {
        appPrefsWrapper.setSiteJetpackCapabilities(
                remoteSiteId,
                capabilities
        )
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    @SuppressWarnings("unused")
    fun onJetpackCapabilitiesFetched(event: OnJetpackCapabilitiesFetched) {
        dispatcher.unregister(this@JetpackCapabilitiesUseCase)
        continuation?.resume(event)
        continuation = null
    }

    data class JetpackPurchasedProducts(val scan: Boolean, val backup: Boolean)
}
