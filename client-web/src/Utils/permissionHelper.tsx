// const evaluatePermission = (permission: string, roleDefinitions: Array<String>) => {
//   let isPermitted = false;

//   for (const role of roleDefinitions) {
//     let skip = false;
//     for (const perm of role.not) {
//       const pattern = `^${perm.replace(".", "\\.").replace("*", ".*")}$`;
//       if (RegExp(pattern).test(permission)) {
//         skip = true;
//         break;
//       }
//     }
//     if (skip) {
//       continue;
//     }
//     for (const perm of role.permissions) {
//       const pattern = `^${perm.replace(".", "\\.").replace("*", ".*")}$`;

//       if (RegExp(pattern).test(permission)) {
//         isPermitted = true;
//       }
//     }
//   }

//   return isPermitted;
// };
// export { evaluatePermission };
