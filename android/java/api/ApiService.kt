package com.example.mark.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface ApiService {
    
    @GET("/api/account-metrics")
    suspend fun getAccountMetrics(): Response<AccountMetrics>
    
    @GET("/api/trades/active")
    suspend fun getActiveTrades(): Response<List<TradeData>>
    
    @GET("/api/trades/historical")
    suspend fun getHistoricalTrades(): Response<List<TradeData>>
    
    @GET("/api/sum-in-exness")
    suspend fun getSumInExness(): Response<SumInExnessResponse>
    
    @POST("/api/switch-account")
    suspend fun switchAccount(@Body request: SwitchAccountRequest): Response<SwitchAccountResponse>
}
