package ws.osiris.awsdeploy.cloudformation

internal fun ResourceTemplate.partitionChildren(
    firstPartitionMaxSize: Int,
    partitionMaxSize: Int
): List<List<ResourceTemplate>> {

    val firstPartition = partition(firstPartitionMaxSize, children)
    val resources = mutableListOf<List<ResourceTemplate>>()
    var partitionedCount = firstPartition.size
    var remaining = children.subList(partitionedCount, children.size)
    resources.add(firstPartition)
    while (partitionedCount < children.size) {
        val partition = partition(partitionMaxSize, remaining)
        partitionedCount += partition.size
        resources.add(partition)
        remaining = children.subList(partitionedCount, children.size)
    }
    return resources
}

private fun partition(maxSize: Int, nodes: List<ResourceTemplate>): List<ResourceTemplate> {
    // This happens if there is nothing but the root node
    if (nodes.isEmpty()) return listOf()
    // TODO is there a sane way to do this without all the mutation? or maybe with just mutation of the list?
    var curCount = 0
    var idx = 0
    val resources = mutableListOf<ResourceTemplate>()
    while (idx < nodes.size) {
        val node = nodes[idx]
        curCount += node.resourceCount
        if (curCount <= maxSize) {
            resources.add(node)
            idx++
        } else {
            break
        }
    }
    if (resources.isEmpty()) {
        throw IllegalStateException("Failed to partition resources")
    }
    return resources
}
