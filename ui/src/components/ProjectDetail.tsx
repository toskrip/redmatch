import React, { ReactNode, useContext, useState } from "react";
import { createStyles, makeStyles } from "@material-ui/core/styles";
import {
  AppBar,
  Box,
  Breadcrumbs,
  Card,
  Link,
  Tab,
  Tabs,
  Typography
} from "@material-ui/core";
import { Link as RouterLink } from "react-router-dom";
import { useQuery } from "react-query";
import RedmatchApi, { RedmatchProject, Mapping } from "../api/RedmatchApi";
import { Config } from "./App";
import { ApiError } from "./ApiError";
import ProjectInfo from "./ProjectInfo";
import ProjectMetadata from "./ProjectMetadata";
import Rules from "./Rules";
import Mappings from "./Mappings";
import { useMutation, queryCache } from "react-query";


export interface CodeSystem {
  url: string;
  name: string;
  vs: string;
}

const useStyles = makeStyles(theme =>
  createStyles({
    link: {
      color: theme.palette.primary.main,
      textDecoration: "none",
      "&:hover": {
        textDecoration: "underline"
      }
    },
    root: {
      display: "flex",
      flexDirection: "column"
    },
    breadcrumbs: {
      marginBottom: "16px"
    },
    content: {
      flexGrow: 1,
      display: "flex",
      flexDirection: "column"
    },
    tabContent: {
      flexGrow: 1,
      padding: "16px"
    }
  })
);

interface Props {
  className?: string;
  reportId: string;
}

interface TabPanelProps {
  className?: string;
  children?: ReactNode;
  index: any;
  value: any;
}

export default function ProjectDetail(props: Props) {
  const classes = useStyles();
  const { redmatchUrl, terminologyUrl } = useContext(Config);
  const { className, reportId } = props;
  const [activeTab, setActiveTab] = useState<number>(0);
  const { status, data: project, error, refetch } = 
    useQuery<RedmatchProject, [string, string]>(
      ["RedmatchProject", reportId], RedmatchApi(redmatchUrl).getProject
    );

  const handleOnSaveRules = (newRules: string) => {
    console.log('Save detected... handling mutation');
    // Rules were updated so run mutation
    updateRules([reportId, newRules]);
  }
  
  // TODO: update type
  const handleOnSaveMappings = (_newMappings: Mapping[]) => {
    console.log('Save mappings detected... handling mutation');
  }

  const [updateRules, { status: updateStatus, error: updateError }] = 
    useMutation<RedmatchProject,[string, string]>(
      RedmatchApi(redmatchUrl).updateRules, {
        onMutate: (params: string[]) => {
          console.log('Param 0' + params[0]);
          console.log('Param 1' + params[1]);

          queryCache.cancelQueries('RedmatchProject');
       
          const previousProject = queryCache.getQueryData('RedmatchProject');
          console.log('Previous: ' + previousProject);
      
          // Optimistically update to the new value
          // Need to clone old project and set new rules
          const newProject: RedmatchProject = 
            Object.assign(
              {
                id:'', 
                reportId: '', 
                redcapUrl: '', 
                token: '', 
                name: '', 
                rulesDocument: '', 
                metadata: {
                  fields: []
                },
                mappings: [],
                issues: []
              }, 
              previousProject);
          newProject.rulesDocument = params[1];
          queryCache.setQueryData('RedmatchProject', newProject);
      
          // Return the snapshotted value
          return () => queryCache.setQueryData('RedmatchProject', previousProject);
        },
        onError: (_err: Error, rollback: any) => rollback(),
        onSettled: () => {
          console.log('Settled');
          queryCache.invalidateQueries('RedmatchProject')
        }
      }
    );

  function renderProjectDetail() {
    if (!project) {
      return (
        <Typography className={classes.content} variant="body1">
          No data
        </Typography>
      );
    }
    return (
      <Card className={classes.content}>
        <AppBar position="static" color="inherit">
          <Tabs value={activeTab}>
            <Tab label="Info" onClick={() => setActiveTab(0)} />
            <Tab label="Metadata" onClick={() => setActiveTab(1)} />
            <Tab label="Rules" onClick={() => setActiveTab(2)} />
            <Tab label="Mappings" onClick={() => setActiveTab(3)} />
          </Tabs>
        </AppBar>
        <TabPanel className={classes.tabContent} index={0} value={activeTab}>
          <ProjectInfo project={project} />
        </TabPanel>
        <TabPanel className={classes.tabContent} index={1} value={activeTab}>
          <ProjectMetadata project={project} />
        </TabPanel>
        <TabPanel className={classes.tabContent} index={2} value={activeTab}>
          <Rules project={project} onSave={handleOnSaveRules} updateStatus={updateStatus}/>
        </TabPanel>
        <TabPanel className={classes.tabContent} index={3} value={activeTab}>
          <Mappings project={project} onSave={handleOnSaveMappings} updateStatus={updateStatus}/>
        </TabPanel>
      </Card>
    );
  }

  return (
    <Box className={`${className} ${classes.root}`}>
      <Breadcrumbs className={classes.breadcrumbs}>
        <Link
          variant="body1"
          component={RouterLink}
          to="/"
        >Home</Link>
        <Typography variant="body1">Projects</Typography>
        <Typography variant="body1">{reportId}</Typography>
      </Breadcrumbs>
      {status === "loading" ? (
        <Typography className={classes.content} variant="body1">
          Loading project...
        </Typography>
      ) : (
        renderProjectDetail()
      )}
      <ApiError error={error} />
    </Box>
  );
}

function TabPanel(props: TabPanelProps) {
  const { className, children, index, value } = props;
  return index === value ? <Box className={className}>{children}</Box> : null;
}
