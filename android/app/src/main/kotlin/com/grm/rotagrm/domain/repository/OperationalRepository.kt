package com.grm.rotagrm.domain.repository

import com.grm.rotagrm.domain.model.OperationalSnapshot

interface OperationalRepository {
    suspend fun fetchSnapshot(): OperationalSnapshot
}
