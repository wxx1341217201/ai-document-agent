package com.wxx.aidocumentagent.vector;

import io.qdrant.client.grpc.Collections.Distance;

/** 当前 collection 使用的向量距离类型。 */
public enum VectorDistance {
    COSINE(Distance.Cosine),
    EUCLID(Distance.Euclid),
    DOT(Distance.Dot),
    MANHATTAN(Distance.Manhattan);

    private final Distance qdrantDistance;

    VectorDistance(Distance qdrantDistance) {
        this.qdrantDistance = qdrantDistance;
    }

    public Distance qdrantDistance() {
        return qdrantDistance;
    }
}
