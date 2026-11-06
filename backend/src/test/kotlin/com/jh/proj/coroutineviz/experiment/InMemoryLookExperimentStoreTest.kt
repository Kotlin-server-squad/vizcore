package com.jh.proj.coroutineviz.experiment

class InMemoryLookExperimentStoreTest : LookExperimentStoreContract() {
    override fun newStore(maxVisitors: Int): LookExperimentStore = InMemoryLookExperimentStore(maxVisitors)
}
