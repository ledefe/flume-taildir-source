# flume-taildir-source
基于flume1.7扩展

1.支持递归目录监控

2.支持多行合并和匹配

示例：

  #指定sources的类型
  
  a1.sources.r1.type = TAILDIR
  
  a1.sources.r1.channels = c1
  
  #指定文件元数据存储路径
  
  a1.sources.r1.positionFile=/var/log/flume/taildir_position.json
  
  #监控分组，这里可兼容多个目录
  
  a1.sources.r1.filegroups=f1
  
  #监控/tmp/logs/xxx目录
  
  a1.sources.r1.filegroups.f1 =/tmp/logs/xxx
  
  #自定义字段，存入header中
  
  a1.sources.r1.headers.f1.indexName=vccloud
  
  a1.sources.r1.headers.f1.instance=vccloud-202
  
  #配置多行匹配规则
  
  a1.sources.r1.extension.f1.multilinePattern=^[0-9]{4}-(((0[13578]|(10|12))-(0[1-9]|[1-2][0-9]|3[0-1]))|(02-(0[1-9]|[1-2][0-9]))|((0[469]|11)-(0[1-9]|[1-2][0-9]|30)))(\\ |T)(([0-1]?[0-9])|([2][0-3])):([0-5]?[0-9]):(([0-5]?[0-9]))\\.[0-9]{3}([\\s\\S]*)
  '''
