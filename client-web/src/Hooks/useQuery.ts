import { useQuery as rqUseQuery } from "react-query";
import { resolver } from "Config/servicesConfig";

export default function useQuery<Response = any, Err = Error>(url: string, ...props: Array<any>) {
  return rqUseQuery<Response, Err>(url, resolver.query(url), ...props);
}
