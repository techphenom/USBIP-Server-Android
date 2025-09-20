package com.techphenom.usbipserver.di

import com.techphenom.usbipserver.data.UsbIpRepository
import com.techphenom.usbipserver.data.UsbIpRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideUsbIpRepository(): UsbIpRepository {
        return UsbIpRepositoryImpl()
    }
}