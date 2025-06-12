package pw.binom

class DomainTree<T> {

    inner class Path(val parent: Path?, val name: String) {
        var value: T? = null
        private val childs = HashMap<String, Path>()
        fun getOrCreate(key: String) = childs.getOrPut(key) { Path(this, name = key) }
        fun get(key: String): Path? {
            val r = childs[key]
            if (r != null) {
                return r
            }
            return childs["*"]
        }

        fun fullPath(): String {
            if (parent == null) {
                return name
            }
            return "$name.${parent.fullPath()}"
        }
    }

    private val root = Path(parent = null, name = "")

    fun get(domain: List<String>): Path? {
        val reversed = domain.asReversed()
        var root = root
        reversed.forEach {
            root = root.get(it) ?: return null
        }
        return root
    }

    fun getOrPut(domain: String): Path {
        val reversed = domain.split('.').asReversed()
        return reversed.fold(root) { a, b ->
            a.getOrCreate(b)
        }
    }
}