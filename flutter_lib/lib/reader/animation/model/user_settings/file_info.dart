
import 'package:flutter_lib/utils/compare_helper.dart';

import 'file_encryption_info.dart';

class FileInfo {

   final String path;
   final FileEncryptionInfo encryptionInfo;

   FileInfo({required this.path, required this.encryptionInfo});

   @override
   bool operator ==(Object other) {
      if (this == other) {
         return true;
      }
      if (other is! FileInfo) {
         return false;
      }
      return path == other.path && CompareHelper.equals(encryptionInfo, other.encryptionInfo);
   }

   @override
   int get hashCode {
      return path.hashCode + 23 * CompareHelper.createHashCode(encryptionInfo);
   }
}