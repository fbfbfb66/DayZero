package com.goings.dayzero.domain.usecase

import com.goings.dayzero.domain.model.media.MediaAsset
import com.goings.dayzero.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class ObserveMediaByIdsUseCase private constructor(
    private val observe: (List<String>) -> Flow<List<MediaAsset>>
) {
    constructor(mediaRepository: MediaRepository) : this(mediaRepository::observeMediaByIds)

    /** Test-only empty observer; production construction is supplied by Hilt with MediaRepository. */
    constructor() : this({ flowOf(emptyList()) })

    operator fun invoke(ids: List<String>): Flow<List<MediaAsset>> = observe(ids)
}
