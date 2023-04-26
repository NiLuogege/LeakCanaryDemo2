package leakcanary

class MyTest : (String)->Unit {
    override fun invoke(p1: String) {
        println("aaaa=$p1")
    }
}