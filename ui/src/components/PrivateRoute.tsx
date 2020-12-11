import * as React from 'react'
import { Route, Redirect, RouteProps } from 'react-router-dom'

import { useKeycloak } from '@react-keycloak/web'

const PrivateRoute : React.FunctionComponent<RouteProps> = props => {
  const { keycloak } = useKeycloak();
  return (
    (() => {
      console.log('keycloak auth: ' + keycloak?.authenticated);
      if(keycloak?.authenticated) {
        return <Route {... props} />;
      } else {
        return <Redirect
               to={{
                 pathname: '/login',
                 state: { from: props.location },
               }}
             />;
      }
    })()
  )
};
export default PrivateRoute;