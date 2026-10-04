package dev.zfold.assistant;

import java.util.ArrayList;
import java.util.List;

final class LocalFileAttachment {
  final String name,mime,kind,detail,excerpt;
  final String image;
  final List<String> images;
  final long size;

  LocalFileAttachment(String name,String mime,String kind,long size,String detail,String excerpt,String image,List<String> images){
    this.name=name;this.mime=mime;this.kind=kind;this.size=size;this.detail=detail;this.excerpt=excerpt;
    this.image=image;this.images=images==null?new ArrayList<>():images;
  }

  boolean isImage(){return "image".equals(kind);}
  boolean hasModelImage(){return image!=null||!images.isEmpty();}
}
