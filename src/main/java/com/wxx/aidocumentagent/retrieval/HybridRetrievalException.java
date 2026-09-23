package com.wxx.aidocumentagent.retrieval;

import com.wxx.aidocumentagent.common.api.BusinessException;

/** 双通道都不能提供结果时使用的明确业务错误，不暴露下游异常细节。 */
public final class HybridRetrievalException extends BusinessException {

    public HybridRetrievalException() {
        super(RetrievalErrorCode.RETRIEVAL_ALL_CHANNELS_FAILED);
    }
}
