package io.github.codexmobilebridge.android

/** A bounded text terminal. OSC/DCS payloads, including clipboard requests, are never exposed. */
class NativeTerminalBuffer {
    private val lines=mutableListOf(StringBuilder())
    private var row=0;private var column=0;private var pending="";private var saved=0 to 0
    fun clear(){lines.clear();lines.add(StringBuilder());row=0;column=0;pending=""}
    fun text()=lines.joinToString("\n"){it.toString().trimEnd()}.trimEnd()
    private fun ensure(){row=row.coerceIn(0,999);column=column.coerceIn(0,499);while(lines.size<=row)lines.add(StringBuilder())}
    fun append(chunk:String){
        val source=pending+chunk;pending="";var i=0
        while(i<source.length){
            val ch=source[i]
            if(ch=='\u001b'){
                if(i+1>=source.length){pending=source.substring(i);break}
                val kind=source[i+1]
                if(kind=='['){
                    var end=i+2;while(end<source.length && source[end] !in '@'..'~')end++
                    if(end>=source.length){pending=source.substring(i).take(4096);break}
                    val args=source.substring(i+2,end).trimStart('?').split(';').map{it.toIntOrNull() ?: 0};val n=(args.firstOrNull() ?: 0).coerceAtLeast(1)
                    when(source[end]){
                        'A'->row-=n;'B'->row+=n;'C'->column+=n;'D'->column-=n
                        'G'->column=n-1;'H','f'->{row=n-1;column=(args.getOrNull(1) ?: 1).coerceAtLeast(1)-1}
                        'J'->{if(args.firstOrNull() in listOf(2,3)){clear()}else{ensure();while(lines.size>row+1)lines.removeAt(lines.lastIndex);lines[row].setLength(minOf(column,lines[row].length))}}
                        'K'->{ensure();when(args.firstOrNull()){2->lines[row].setLength(0);1->{for(x in 0 until minOf(column+1,lines[row].length))lines[row].setCharAt(x,' ')};else->lines[row].setLength(minOf(column,lines[row].length))}}
                        's'->saved=row to column;'u'->{row=saved.first;column=saved.second}
                    };ensure();i=end+1;continue
                }
                if(kind in listOf(']','P','^','_')){
                    var end=i+2;while(end<source.length && source[end]!='\u0007' && !(source[end]=='\u001b' && end+1<source.length && source[end+1]=='\\'))end++
                    if(end>=source.length){pending=source.substring(i).take(4096);break}
                    i=end+if(source[end]=='\u001b')2 else 1;continue
                }
                if(kind=='7')saved=row to column else if(kind=='8'){row=saved.first;column=saved.second}
                i+=2;continue
            }
            when(ch){
                '\r'->column=0
                '\n'->{row++;column=0;if(row>=1000){lines.removeAt(0);row=999};ensure()}
                '\b'->column=(column-1).coerceAtLeast(0)
                '\t'->column=((column/8+1)*8).coerceAtMost(499)
                else->if(ch>=' ' && ch!='\u007f'){ensure();val line=lines[row];while(line.length<=column)line.append(' ');line.setCharAt(column,ch);column++}
            };i++
        }
    }
}
